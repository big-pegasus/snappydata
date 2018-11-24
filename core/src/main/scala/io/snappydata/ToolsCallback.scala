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
import java.net.URLClassLoader

import org.apache.spark.SparkContext

trait ToolsCallback {

  def updateUI(sc: SparkContext): Unit

  def removeAddedJar(sc: SparkContext, jarName: String): Unit

  /**
   * Callback to spark Utils to fetch file
   * Download a file or directory to target directory. Supports fetching the file in a variety of
   * ways, including HTTP, Hadoop-compatible filesystems, and files on a standard filesystem, based
   * on the URL parameter. Fetching directories is only supported from Hadoop-compatible
   * filesystems.
   *
   * If `useCache` is true, first attempts to fetch the file to a local cache that's shared
   * across executors running the same application. `useCache` is used mainly for
   * the executors, and not in local mode.
   *
   * Throws SparkException if the target file already exists and has different contents than
   * the requested file.
   */
  def doFetchFile(
      url: String,
      targetDir: File,
      filename: String): File

  def setSessionDependencies(sparkContext: SparkContext,
      appName: String,
      classLoader: ClassLoader): Unit = {
  }

  def addURIs(alias: String, jars: Array[String],
      deploySql: String, isPackage: Boolean = true): Unit

  def addURIsToExecutorClassLoader(jars: Array[String]): Unit

  def getAllGlobalCmnds: Array[String]

  def getGlobalCmndsSet: java.util.Set[java.util.Map.Entry[String, String]]

  def removePackage(alias: String): Unit

  def setLeadClassLoader(): Unit

  def getLeadClassLoader: URLClassLoader

  /**
   * Check whether a user has permission to change the given schema (e.g. add/drop tables)
   *
   * @param schema      the schema name for the permission check
   * @param currentUser the user for the permission check
   * @return if permission is allowed, return the schema owner which can be ldap group
   *         else this throws an appropriate StandardException
   */
  def checkSchemaPermission(schema: String, currentUser: String): String
}
