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
package io.snappydata

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.iapi.error.StandardException
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection
import com.pivotal.gemfirexd.internal.impl.sql.execute.PrivilegeInfo
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState
import io.snappydata.cluster.ExecutorInitiator
import io.snappydata.impl.LeadImpl

import org.apache.spark.executor.SnappyExecutor
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.ui.SQLTab
import org.apache.spark.sql.hive.thriftserver.SnappyHiveThriftServer2
import org.apache.spark.ui.{JettyUtils, SnappyDashboardTab}
import org.apache.spark.util.SnappyUtils
import org.apache.spark.{Logging, SparkCallbacks, SparkContext}

object ToolsCallbackImpl extends ToolsCallback with Logging {

  override def updateUI(sc: SparkContext): Unit = {

    SnappyUtils.getSparkUI(sc).foreach(ui => {
      // Create Snappy Dashboard and SQL tabs.
      // Set SnappyData authenticator SecurityHandler.
      SparkCallbacks.getAuthenticatorForJettyServer() match {
        case Some(_) =>
          logInfo("Setting auth handler")
          // Set JettyUtils.skipHandlerStart for adding dashboard and sql security handlers
          JettyUtils.skipHandlerStart.set(true)
          // Creating SQL and Dashboard UI tabs
          if (!sc.isLocal) {
            new SQLTab(ExternalStoreUtils.getSQLListener.get(), ui)
          }
          SnappyHiveThriftServer2.attachUI()
          new SnappyDashboardTab(ui)
          // Set security handlers
          ui.getHandlers.foreach { h =>
            if (!h.isStarted) {
              h.setSecurityHandler(JettyUtils.basicAuthenticationHandler())
              h.start()
            }
          }
          // Unset JettyUtils.skipHandlerStart
          JettyUtils.skipHandlerStart.set(false)
        case None => logDebug("Not setting auth handler")
          // Creating SQL and Dashboard UI tabs
          if (!sc.isLocal) {
            new SQLTab(ExternalStoreUtils.getSQLListener.get(), ui)
          }
          SnappyHiveThriftServer2.attachUI()
          new SnappyDashboardTab(ui)
      }
    })
  }

  override def removeAddedJar(sc: SparkContext, jarName: String): Unit =
    sc.removeAddedJar(jarName)

  /**
   * Callback to spark Utils to fetch file
   */
  override def doFetchFile(
      url: String,
      targetDir: File,
      filename: String): File = {
    SnappyUtils.doFetchFile(url, targetDir, filename)
  }

  override def setSessionDependencies(sparkContext: SparkContext, appName: String,
      classLoader: ClassLoader): Unit = {
    SnappyUtils.setSessionDependencies(sparkContext, appName, classLoader)
  }

  override def addURIs(alias: String, jars: Array[String],
      deploySql: String, isPackage: Boolean = true): Unit = {
    if (alias != null) {
      Misc.getMemStore.getGlobalCmdRgn.put(alias, deploySql)
    }
    val lead = ServiceManager.getLeadInstance.asInstanceOf[LeadImpl]
    val loader = lead.urlclassloader
    jars.foreach(j => {
      val url = new File(j).toURI.toURL
      loader.addURL(url)
    })
    // Close and reopen interpreter
    if (alias != null) {
      try {
        lead.closeAndReopenInterpreterServer()
      } catch {
        case ite: InvocationTargetException => assert(ite.getCause.isInstanceOf[SecurityException])
      }
    }
  }

  override def addURIsToExecutorClassLoader(jars: Array[String]): Unit = {
    if (ExecutorInitiator.snappyExecBackend != null) {
      val snappyexecutor = ExecutorInitiator.snappyExecBackend.executor.asInstanceOf[SnappyExecutor]
      snappyexecutor.updateMainLoader(jars)
    }
  }

  override def getAllGlobalCmnds(): Array[String] = {
    GemFireXDUtils.waitForNodeInitialization()
    Misc.getMemStore.getGlobalCmdRgn.values().toArray.map(_.asInstanceOf[String])
  }

  override def getGlobalCmndsSet(): java.util.Set[java.util.Map.Entry[String, String]] = {
    GemFireXDUtils.waitForNodeInitialization()
    Misc.getMemStore.getGlobalCmdRgn.entrySet()
  }

  override def removePackage(alias: String): Unit = {
    GemFireXDUtils.waitForNodeInitialization()
    val packageRegion = Misc.getMemStore.getGlobalCmdRgn
    packageRegion.destroy(alias)
  }

  override def setLeadClassLoader(): Unit = {
    val instance = ServiceManager.currentFabricServiceInstance
    instance match {
      case li: LeadImpl =>
        val loader = li.urlclassloader
        if (loader != null) {
          Thread.currentThread().setContextClassLoader(loader)
        }
      case _ =>
    }
  }

  override def getLeadClassLoader(): URLClassLoader = {
    var ret: URLClassLoader = null
    val instance = ServiceManager.currentFabricServiceInstance
    instance match {
      case li: LeadImpl =>
        val loader = li.urlclassloader
        if (loader != null) {
          ret = loader
        }
      case _ =>
    }
    ret
  }

  override def checkSchemaPermission(schema: String, currentUser: String): String = {
    val ms = Misc.getMemStoreBootingNoThrow
    if (ms != null) {
      var conn: EmbedConnection = null
      if (ms.isSnappyStore && Misc.isSecurityEnabled) {
        var contextSet = false
        try {
          val dd = ms.getDatabase.getDataDictionary
          conn = GemFireXDUtils.getTSSConnection(false, true, false)
          conn.getTR.setupContextStack()
          contextSet = true
          val sd = dd.getSchemaDescriptor(
            schema, conn.getLanguageConnection.getTransactionExecute, false)
          if (sd == null) {
            if (schema.equals(currentUser)) {
              if (ms.tableCreationAllowed()) return currentUser
              throw StandardException.newException(SQLState.AUTH_NO_ACCESS_NOT_OWNER,
                schema, schema)
            } else {
              throw StandardException.newException(SQLState.LANG_SCHEMA_DOES_NOT_EXIST, schema)
            }
          }
          PrivilegeInfo.checkOwnership(currentUser, sd, sd, dd)
          sd.getAuthorizationId
        } finally {
          if (contextSet) conn.getTR.restoreContextStack()
        }
      } else {
        currentUser
      }
    } else currentUser
  }
}
