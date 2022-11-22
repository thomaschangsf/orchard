/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.resource

import scala.jdk.CollectionConverters._

import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.amazon.awssdk.services.emr.model._

import com.salesforce.mce.orchard.io.aws.{Client, ProviderSettings}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.RetryHelper._

case class EmrResource(name: String, spec: EmrResource.Spec) extends ResourceIO {
  private val logger = LoggerFactory.getLogger(getClass)

  private val releaseLabel = spec.releaseLabel
  private val instancesConfig = spec.instancesConfig
  private val applications = spec.applications.map(a => Application.builder().name(a).build())

  val loggingUriBase = ProviderSettings().loggingUri.map(p => s"$p$name/")

  override def create(): Either[Throwable, JsValue] = retryToEither {
    val awsTags = spec.tags match {
      case None =>
        logger.debug(s"no tags given")
        List.empty[Tag]
      case Some(ts) =>
        logger.debug(s"spec.tags=${spec.tags}")
        ts.map(tag => Tag.builder().key(tag.key).value(tag.value).build())
    }

    val unwrappedBootstrapActions = spec.bootstrapActions match {
      case None => Seq.empty
      case Some(bas) => bas
    }

    val response = Client
      .emr()
      .runJobFlow(
        loggingUriBase
          .foldLeft(
            RunJobFlowRequest
              .builder()
              .name(name)
              .releaseLabel(releaseLabel)
              .applications(applications: _*)
              .serviceRole(spec.serviceRole)
              .jobFlowRole(spec.resourceRole)
              .bootstrapActions(
                unwrappedBootstrapActions.map { ba =>
                  BootstrapActionConfig
                    .builder()
                    .scriptBootstrapAction(
                      ScriptBootstrapActionConfig
                        .builder()
                        .path(ba.path)
                        .args(ba.args: _*)
                        .build()
                    )
                    .build()
                }: _*
              )
              .tags(awsTags: _*)
              .configurations(EmrResource.asConfigurations(spec.configurations): _*)
              .instances {
                val builder = JobFlowInstancesConfig
                  .builder()
                  .ec2SubnetId(instancesConfig.subnetId)
                  .ec2KeyName(instancesConfig.ec2KeyName)
                  .instanceCount(instancesConfig.instanceCount)
                  .masterInstanceType(instancesConfig.masterInstanceType)
                  .slaveInstanceType(instancesConfig.slaveInstanceType)
                  .keepJobFlowAliveWhenNoSteps(true)

                instancesConfig.additionalMasterSecurityGroups
                  .foldLeft(builder)(_.additionalMasterSecurityGroups(_: _*))
                instancesConfig.additionalSlaveSecurityGroups
                  .foldLeft(builder)(_.additionalSlaveSecurityGroups(_: _*))

                builder.build()
              }
          )((r, uri) => r.logUri(uri))
          .build()
      )
    logger.debug(s"create: name=$name jobFlowId=${response.jobFlowId()}")
    Json.toJson(EmrResource.InstSpec(response.jobFlowId()))
  }

  private def getStatus(spec: EmrResource.InstSpec) = {
    val response = Client
      .emr()
      .describeCluster(DescribeClusterRequest.builder().clusterId(spec.clusterId).build())
      .retry()

    response.cluster().status().state() match {
      case ClusterState.BOOTSTRAPPING =>
        Right(Status.Activating)
      case ClusterState.RUNNING =>
        Right(Status.Running)
      case ClusterState.STARTING =>
        Right(Status.Activating)
      case ClusterState.TERMINATED =>
        Right(Status.Finished)
      case ClusterState.TERMINATED_WITH_ERRORS =>
        Right(Status.Failed)
      case ClusterState.TERMINATING =>
        Right(Status.Finished)
      case ClusterState.WAITING =>
        Right(Status.Running)
      case ClusterState.UNKNOWN_TO_SDK_VERSION =>
        Left(new Exception("UNKNOWN_TO_SDK_VERSION"))
    }

  }

  override def getStatus(instSpec: JsValue): Either[Throwable, Status.Value] = instSpec
    .validate[EmrResource.InstSpec]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => getStatus(valid)
    )

  private def terminate(spec: EmrResource.InstSpec) = {
    Client
      .emr()
      .terminateJobFlows(TerminateJobFlowsRequest.builder().jobFlowIds(spec.clusterId).build())
      .retry()

    Status.Finished
  }

  override def terminate(instSpec: JsValue): Either[Throwable, Status.Value] = instSpec
    .validate[EmrResource.InstSpec]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => Right(terminate(valid))
    )

}

object EmrResource {

  case class InstSpec(clusterId: String)

  implicit val instSpecWrites: Writes[InstSpec] = Json.writes[InstSpec]
  implicit val instSpecReads: Reads[InstSpec] = Json.reads[InstSpec]

  case class InstancesConfig(
    subnetId: String,
    ec2KeyName: String,
    instanceCount: Int,
    masterInstanceType: String,
    slaveInstanceType: String,
    additionalMasterSecurityGroups: Option[Seq[String]],
    additionalSlaveSecurityGroups: Option[Seq[String]]
  )
  implicit val instancesConfigReads: Reads[InstancesConfig] = Json.reads[InstancesConfig]

  case class ConfigurationSpec(
    classification: String,
    properties: Option[Map[String, String]],
    configurations: Option[Seq[ConfigurationSpec]]
  )
  implicit val configurationSpecReads: Reads[ConfigurationSpec] = Json.reads[ConfigurationSpec]

  def asConfigurations(configurations: Option[Seq[ConfigurationSpec]]): Seq[Configuration] = {

    def parse(configSpec: ConfigurationSpec): Configuration = {
      val builder = configSpec.properties.foldLeft(
        Configuration.builder().classification(configSpec.classification)
      )((b, ps) => b.properties(ps.asJava))

      for { moreConfigs <- configSpec.configurations } {
        val childConfigs = moreConfigs.map(parse)
        builder.configurations(childConfigs: _*)
      }

      builder.build()
    }

    configurations match {
      case None => List.empty[Configuration]
      case Some(conf) => conf.map(parse)
    }
  }

  case class BootstrapAction(path: String, args: Seq[String])
  implicit val bootstrapActionReads: Reads[BootstrapAction] =
    Json.reads[BootstrapAction]

  case class Spec(
    releaseLabel: String,
    applications: Seq[String],
    serviceRole: String,
    resourceRole: String,
    tags: Option[Seq[AwsTag]],
    bootstrapActions: Option[Seq[BootstrapAction]],
    configurations: Option[Seq[ConfigurationSpec]],
    instancesConfig: InstancesConfig
  )
  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ResourceIO.Conf): JsResult[EmrResource] = conf.resourceSpec
    .validate[Spec]
    .map(spec =>
      EmrResource.apply(s"${conf.workflowId}_rsc-${conf.resourceId}_${conf.instanceId}", spec)
    )

}
