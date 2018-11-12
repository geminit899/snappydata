/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package io.snappydata.util

import java.util.Properties
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import _root_.com.gemstone.gemfire.distributed.DistributedMember
import _root_.com.gemstone.gemfire.distributed.internal.DistributionConfig
import _root_.com.gemstone.gemfire.distributed.internal.DistributionConfig.ENABLE_NETWORK_PARTITION_DETECTION_NAME
import _root_.com.gemstone.gemfire.internal.shared.ClientSharedUtils
import _root_.com.pivotal.gemfirexd.internal.engine.GfxdConstants
import _root_.com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import io.snappydata.{Constant, Property, ServerManager}

import org.apache.spark.SparkContext
import org.apache.spark.sql.collection.Utils

/**
 * Common utility methods for store services.
 */
object ServiceUtils {

  val LOCATOR_URL_PATTERN: Pattern = Pattern.compile("(.+:[0-9]+)|(.+\\[[0-9]+\\])")

  private[snappydata] def getStoreProperties(
      confProps: Seq[(String, String)]): Properties = {
    val storeProps = new Properties()
    confProps.foreach {
      case (Property.Locators(), v) =>
        if (!LOCATOR_URL_PATTERN.matcher(v).matches()) {
          throw Utils.analysisException(s"locators property $v should " +
              "be provided in the format host[port] or host:port")
        }
        storeProps.setProperty("locators", v)
      case (k, v) if k.startsWith(Constant.STORE_PROPERTY_PREFIX) =>
        storeProps.setProperty(k.trim.replaceFirst(
          Constant.STORE_PROPERTY_PREFIX, ""), v)
      case (k, v) if k.startsWith(Constant.SPARK_STORE_PREFIX) =>
        storeProps.setProperty(k.trim.replaceFirst(
          Constant.SPARK_STORE_PREFIX, ""), v)
      case (k, v) if k.startsWith(Constant.SPARK_PREFIX) ||
          k.startsWith(Constant.PROPERTY_PREFIX) ||
          k.startsWith(Constant.JOBSERVER_PROPERTY_PREFIX) => storeProps.setProperty(k, v)
      case _ => // ignore rest
    }
    setCommonBootDefaults(storeProps, forLocator = false)
  }

  private[snappydata] def setCommonBootDefaults(props: Properties,
      forLocator: Boolean): Properties = {
    val storeProps = if (props ne null) props else new Properties()
    if (!forLocator) {
      // set default recovery delay to 2 minutes (SNAP-1541)
      if (storeProps.getProperty(GfxdConstants.DEFAULT_STARTUP_RECOVERY_DELAY_PROP) == null) {
        storeProps.setProperty(GfxdConstants.DEFAULT_STARTUP_RECOVERY_DELAY_PROP, "120000")
      }
      // try hard to maintain executor and node locality
      if (storeProps.getProperty("spark.locality.wait.process") == null) {
        storeProps.setProperty("spark.locality.wait.process", "20s")
      }
      if (storeProps.getProperty("spark.locality.wait") == null) {
        storeProps.setProperty("spark.locality.wait", "10s")
      }
    }
    // set default member-timeout higher for GC pauses (SNAP-1777)
    if (storeProps.getProperty(DistributionConfig.MEMBER_TIMEOUT_NAME) == null) {
      storeProps.setProperty(DistributionConfig.MEMBER_TIMEOUT_NAME, "30000")
    }
    // set network partition detection by default
    if (storeProps.getProperty(ENABLE_NETWORK_PARTITION_DETECTION_NAME) == null) {
      storeProps.setProperty(ENABLE_NETWORK_PARTITION_DETECTION_NAME, "true")
    }
    storeProps
  }

  def invokeStartFabricServer(sc: SparkContext,
      hostData: Boolean): Unit = {
    val properties = getStoreProperties(Utils.getInternalSparkConf(sc).getAll)
    // overriding the host-data property based on the provided flag
    if (!hostData) {
      properties.setProperty("host-data", "false")
      // no DataDictionary persistence for non-embedded mode
      properties.setProperty("persist-dd", "false")
    }
    // set the log-level from initialized SparkContext's level if set to higher level than default
    if (!properties.containsKey("log-level")) {
      val level = org.apache.log4j.Logger.getRootLogger.getLevel
      if ((level ne null) && level.isGreaterOrEqual(org.apache.log4j.Level.WARN)) {
        properties.setProperty("log-level",
          ClientSharedUtils.convertToJavaLogLevel(level).getName.toLowerCase)
      }
    }
    ServerManager.getServerInstance.start(properties)

    // initialize cluster callbacks if possible by reflection
    try {
      Utils.classForName("io.snappydata.gemxd.ClusterCallbacksImpl$")
    } catch {
      case _: ClassNotFoundException => // ignore if failed to load
    }
  }

  def invokeStopFabricServer(sc: SparkContext, shutDownCreds: Properties = null): Unit = {
    ServerManager.getServerInstance.stop(shutDownCreds)
  }

  def getAllLocators(sc: SparkContext): scala.collection.Map[DistributedMember, String] = {
    val advisor = GemFireXDUtils.getGfxdAdvisor
    val locators = advisor.adviseLocators(null)
    val locatorServers = scala.collection.mutable.HashMap[DistributedMember, String]()
    locators.asScala.foreach(locator =>
      locatorServers.put(locator,
        if (ClientSharedUtils.isThriftDefault) advisor.getThriftServers(locator)
        else advisor.getDRDAServers(locator)))
    locatorServers
  }

  def getLocatorJDBCURL(sc: SparkContext): String = {
    val locatorUrl = getAllLocators(sc).filter(x => x._2 != null && !x._2.isEmpty)
        .map(locator => {
          org.apache.spark.sql.collection.Utils.getClientHostPort(locator._2)
        }).mkString(",")

    Constant.DEFAULT_THIN_CLIENT_URL + (if (locatorUrl.contains(",")) {
      locatorUrl.substring(0, locatorUrl.indexOf(",")) +
          "/;secondary-locators=" + locatorUrl.substring(locatorUrl.indexOf(",") + 1)
    } else locatorUrl + "/")
  }

  def clearStaticArtifacts(): Unit = {
  }
}
