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

package org.apache.spark.sql.internal

import java.io.File
import java.net.{URL, URLClassLoader}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import org.apache.spark.{Logging, SparkFiles}
import org.apache.spark.sql.SnappyContext
import org.apache.spark.sql.collection.ToolsCallbackInit
import org.apache.spark.util.MutableURLClassLoader

/**
  * An utility class to store jar file reference with their individual class loaders.
  * This is to reflect class changes at driver side.
  * e.g. If an UDF definition changes the driver should pick up the correct UDF class.
  * This class can not initialize itself after a driver failure.
  * So the callers will have  to make sure that the classloader gets
  * initialized after a driver startup. Usually it can be achieved by
  * adding classloader at query time.
  *
  */
object ContextJarUtils extends Logging {
  val JAR_PATH = "snappy-jars"
  private val driverJars = new ConcurrentHashMap[String, URLClassLoader]().asScala

  def addDriverJar(key: String, classLoader: URLClassLoader): Option[URLClassLoader] = {
    driverJars.putIfAbsent(key, classLoader)
  }

  def addIfNotPresent(key: String, urls: Array[URL], parent: ClassLoader): Unit = {
    if (driverJars.get(key).isEmpty) {
      driverJars.putIfAbsent(key, new MutableURLClassLoader(urls, parent))
    }
  }

  def getDriverJar(key: String): Option[URLClassLoader] = driverJars.get(key)

  def removeDriverJar(key: String) : Unit = driverJars.remove(key)

  /**
    * This method will copy the given jar to Spark root directory
    * and then it will add the same jar to Spark.
    * This ensures class & jar isolation although it might be repetitive.
    *
    * @param prefix prefix to be given to the jar name
    * @param path   original path of the jar
    */
  def fetchFile(prefix: String, path: String): URL = {
    val callbacks = ToolsCallbackInit.toolsCallback
    val localName = path.split("/").last
    val changedFileName = s"${prefix}-${localName}"
    logInfo(s"Fetching jar $path to driver local directory $jarDir")
    val changedFile = new File(jarDir, changedFileName)
    if (!changedFile.exists()) {
      callbacks.doFetchFile(path, jarDir, changedFileName)
    }
    new File(jarDir, changedFileName).toURI.toURL
  }

  def deleteFile(prefix: String, path: String): Unit = {
    def getName(path: String): String = new File(path).getName

    val callbacks = ToolsCallbackInit.toolsCallback
    if (callbacks != null) {
      val localName = path.split("/").last
      val changedFileName = s"${prefix}-${localName}"
      val jarFile = new File(jarDir, changedFileName)

      if (jarFile.exists()) {
        jarFile.delete()
      }
    }
  }

  private def sparkContext = SnappyContext.globalSparkContext

  private def jarDir = {
    val jarDirectory = new File(System.getProperty("user.dir"), JAR_PATH)
    if (!jarDirectory.exists()) jarDirectory.mkdir()
    jarDirectory
  }
}


