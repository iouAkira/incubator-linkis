/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.linkis.entrance.log

import org.apache.linkis.common.io.FsPath
import org.apache.linkis.common.utils.Utils
import org.apache.linkis.entrance.conf.EntranceConfiguration
import org.apache.linkis.entrance.exception.EntranceErrorException
import org.apache.linkis.storage.FSFactory
import org.apache.linkis.storage.fs.FileSystem
import org.apache.linkis.storage.utils.FileSystemUtils

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream
import org.apache.hadoop.io.IOUtils

import java.io.{IOException, OutputStream}
import java.sql.Date
import java.util

class HDFSCacheLogWriter(logPath: String, charset: String, sharedCache: Cache, user: String)
    extends LogWriter(charset) {

  if (StringUtils.isBlank(logPath))
    throw new EntranceErrorException(20301, "logPath cannot be empty.")

  protected var fileSystem =
    FSFactory.getFsByProxyUser(new FsPath(logPath), user).asInstanceOf[FileSystem]

  override protected var outputStream: OutputStream = null

  private val OUT_LOCKER = new Object

  private val WRITE_LOCKER = new Object

  private var firstWrite = true

  init()

  private def init(): Unit = {
    fileSystem.init(new util.HashMap[String, String]())
    FileSystemUtils.createNewFileWithFileSystem(fileSystem, new FsPath(logPath), user, true)
  }

  @throws[IOException]
  def getOutputStream: OutputStream = {
    if (null == outputStream) OUT_LOCKER.synchronized {
      if (null == outputStream) {
        if (fileSystem != null) outputStream = fileSystem.write(new FsPath(logPath), false)
        else logger.warn("fileSystem is null")

      }
    }
    outputStream
  }

  private def closeOutPutStream: Unit = {
    if (null != outputStream) OUT_LOCKER.synchronized {
      if (null != outputStream) {
        outputStream match {
          case hdfs: HdfsDataOutputStream =>
            hdfs.hflush()
          case _ =>
        }
        IOUtils.closeStream(outputStream)
        this.outputStream = null
      }
    }
  }

  val pushTime: Date = new Date(
    System.currentTimeMillis() + EntranceConfiguration.LOG_PUSH_INTERVAL_TIME.getValue
  )

  def getCache: Option[Cache] = Some(sharedCache)

  private def cache(msg: String): Unit = {
    WRITE_LOCKER synchronized {
      val removed = sharedCache.cachedLogs.add(msg)
      val currentTime = new Date(System.currentTimeMillis())
      if (removed != null || currentTime.after(pushTime)) {
        val logs = sharedCache.cachedLogs.toList
        val sb = new StringBuilder
        if (removed != null) sb.append(removed).append("\n")
        logs.filter(_ != null).foreach(log => sb.append(log).append("\n"))
        sharedCache.cachedLogs.fakeClear()
        writeToFile(sb.toString())
        pushTime.setTime(
          currentTime.getTime + EntranceConfiguration.LOG_PUSH_INTERVAL_TIME.getValue
        )
      }
    }
  }

  private def writeToFile(msg: String): Unit = WRITE_LOCKER synchronized {
    val log =
      if (!firstWrite) "\n" + msg
      else {
        logger.info(s"$toString write first one line log")
        firstWrite = false
        msg
      }
    Utils.tryAndWarnMsg {
      getOutputStream.write(log.getBytes(charset))
    }(s"$toString error when write query log to outputStream.")
    closeOutPutStream
  }

  override def write(msg: String): Unit = {
    if (StringUtils.isBlank(msg)) {
      cache("")
    } else {
      val rows = msg.split("\n")
      rows.foreach(row => {
        if (row == null) cache("") else cache(row)
      })
    }
  }

  override def flush(): Unit = {
    val sb = new StringBuilder
    sharedCache.cachedLogs.toList
      .filter(StringUtils.isNotEmpty)
      .foreach(sb.append(_).append("\n"))
    sharedCache.cachedLogs.clear()
    writeToFile(sb.toString())
  }

  override def close(): Unit = {
    super.close()
    if (fileSystem != null) Utils.tryAndWarnMsg {
      fileSystem.close()
      fileSystem = null
    }(s"$toString Error encounters when closing fileSystem")
  }

  override def toString: String = logPath

}
