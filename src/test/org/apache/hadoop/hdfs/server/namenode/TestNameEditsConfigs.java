/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import junit.framework.TestCase;
import java.io.*;
import java.util.Collection;
import java.util.List;
import java.util.Iterator;
import java.util.Random;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.FSConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.namenode.FSImage.NameNodeFile;
import org.apache.hadoop.hdfs.server.namenode.SecondaryNameNode.ErrorSimulator;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;

/**
 * This class tests various combinations of dfs.name.dir 
 * and dfs.name.edits.dir configurations.
 */
public class TestNameEditsConfigs extends TestCase {
  static final long seed = 0xDEADBEEFL;
  static final int blockSize = 4096;
  static final int fileSize = 8192;
  static final int numDatanodes = 3;
  short replication = 3;

  private void writeFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, (long)blockSize);
    byte[] buffer = new byte[fileSize];
    Random rand = new Random(seed);
    rand.nextBytes(buffer);
    stm.write(buffer);
    stm.close();
  }
  
  
  private void checkFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    assertTrue(fileSys.exists(name));
    int replication = fileSys.getFileStatus(name).getReplication();
    assertEquals("replication for " + name, repl, replication);
    long size = fileSys.getContentSummary(name).getLength();
    assertEquals("file size for " + name, size, (long)fileSize);
  }
  
  private void cleanupFile(FileSystem fileSys, Path name)
    throws IOException {
    assertTrue(fileSys.exists(name));
    fileSys.delete(name, true);
    assertTrue(!fileSys.exists(name));
  }

  SecondaryNameNode startSecondaryNameNode(Configuration conf
                                          ) throws IOException {
    conf.set("dfs.secondary.http.address", "0.0.0.0:0");
    return new SecondaryNameNode(conf);
  }

  /**
   * Test various configuration options of dfs.name.dir and dfs.name.edits.dir
   * The test creates files and restarts cluster with different configs.
   * 1. Starts cluster with shared name and edits dirs
   * 2. Restarts cluster by adding additional (different) name and edits dirs
   * 3. Restarts cluster by removing shared name and edits dirs by allowing to 
   *    start using separate name and edits dirs
   * 4. Restart cluster by adding shared directory again, but make sure we 
   *    do not read any stale image or edits. 
   * All along the test, we create and delete files at reach restart to make
   * sure we are reading proper edits and image.
   */
  public void testNameEditsConfigs() throws IOException {
    Path file1 = new Path("TestNameEditsConfigs1");
    Path file2 = new Path("TestNameEditsConfigs2");
    Path file3 = new Path("TestNameEditsConfigs3");
    MiniDFSCluster cluster = null;
    SecondaryNameNode secondary = null;
    Configuration conf = null;
    FileSystem fileSys = null;
    File base_dir = new File(System.getProperty("test.build.data"), "dfs/");
    File newNameDir = new File(base_dir, "name");
    File newEditsDir = new File(base_dir, "edits");
    File nameAndEdits = new File(base_dir, "name_and_edits");
    File checkpointNameDir = new File(base_dir, "secondname");
    File checkpointEditsDir = new File(base_dir, "secondedits");
    File checkpointNameAndEdits = new File(base_dir, "second_name_and_edits");
    
    // Start namenode with same dfs.name.dir and dfs.name.edits.dir
    conf = new Configuration();
    conf.set("dfs.name.dir", nameAndEdits.getPath());
    conf.set("dfs.name.edits.dir", nameAndEdits.getPath());
    conf.set("fs.checkpoint.dir", checkpointNameAndEdits.getPath());
    conf.set("fs.checkpoint.edits.dir", checkpointNameAndEdits.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    // Manage our own dfs directories
    cluster = new MiniDFSCluster(0, conf, numDatanodes, true, false, true, null,
                                  null, null, null);
    cluster.waitActive();
    secondary = startSecondaryNameNode(conf);
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(!fileSys.exists(file1));
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
      secondary.doCheckpoint();
    } finally {
      fileSys.close();
      cluster.shutdown();
      secondary.shutdown();
    }

    // Start namenode with additional dfs.name.dir and dfs.name.edits.dir
    conf =  new Configuration();
    assertTrue(newNameDir.mkdir());
    assertTrue(newEditsDir.mkdir());

    conf.set("dfs.name.dir", nameAndEdits.getPath() +
              "," + newNameDir.getPath());
    conf.set("dfs.name.edits.dir", nameAndEdits.getPath() + 
             "," + newEditsDir.getPath());
    conf.set("fs.checkpoint.dir", checkpointNameDir.getPath() +
             "," + checkpointNameAndEdits.getPath());
    conf.set("fs.checkpoint.edits.dir", checkpointEditsDir.getPath() +
             "," + checkpointNameAndEdits.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    // Manage our own dfs directories. Do not format.
    cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true, 
                                  null, null, null, null);
    cluster.waitActive();
    secondary = startSecondaryNameNode(conf);
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(fileSys.exists(file1));
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      writeFile(fileSys, file2, replication);
      checkFile(fileSys, file2, replication);
      secondary.doCheckpoint();
    } finally {
      fileSys.close();
      cluster.shutdown();
      secondary.shutdown();
    }
    
    // Now remove common directory both have and start namenode with 
    // separate name and edits dirs
    conf =  new Configuration();
    conf.set("dfs.name.dir", newNameDir.getPath());
    conf.set("dfs.name.edits.dir", newEditsDir.getPath());
    conf.set("fs.checkpoint.dir", checkpointNameDir.getPath());
    conf.set("fs.checkpoint.edits.dir", checkpointEditsDir.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true,
                                  null, null, null, null);
    cluster.waitActive();
    secondary = startSecondaryNameNode(conf);
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(!fileSys.exists(file1));
      assertTrue(fileSys.exists(file2));
      checkFile(fileSys, file2, replication);
      cleanupFile(fileSys, file2);
      writeFile(fileSys, file3, replication);
      checkFile(fileSys, file3, replication);
      secondary.doCheckpoint();
    } finally {
      fileSys.close();
      cluster.shutdown();
      secondary.shutdown();
    }
    
    // Add old name_and_edits dir. File system should not read image or edits
    // from old dir
    conf = new Configuration();
    conf.set("dfs.name.dir", nameAndEdits.getPath() +
              "," + newNameDir.getPath());
    conf.set("dfs.name.edits.dir", nameAndEdits +
              "," + newEditsDir.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true,
                                  null, null, null, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(!fileSys.exists(file1));
      assertTrue(!fileSys.exists(file2));
      assertTrue(fileSys.exists(file3));
      checkFile(fileSys, file3, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    // Cleanup
    if (!FileUtil.fullyDelete(newNameDir)) 
      throw new IOException("Cannot remove directory " + newNameDir);
    if (!FileUtil.fullyDelete(newEditsDir)) 
      throw new IOException("Cannot remove directory " + newEditsDir);
    if (!FileUtil.fullyDelete(nameAndEdits))
      throw new IOException("Cannot remove directory " + nameAndEdits);
    if (!FileUtil.fullyDelete(checkpointNameDir))
      throw new IOException("Cannot remove directory " + checkpointNameDir);
    if (!FileUtil.fullyDelete(checkpointEditsDir))
      throw new IOException("Cannot remove directory " + checkpointEditsDir);
    if (!FileUtil.fullyDelete(checkpointNameAndEdits))
      throw new IOException("Cannot remove directory " + 
                             checkpointNameAndEdits);
  }

  /**
   * Test various configuration options of dfs.name.dir and dfs.name.edits.dir
   * This test tries to simulate failure scenarios.
   * 1. Start cluster with shared name and edits dir
   * 2. Restart cluster by adding separate name and edits dirs
   * 3. Restart cluster by removing shared name and edits dir
   * 4. Restart cluster with old shared name and edits dir, but only latest 
   *    name dir. This should fail since we dont have latest edits dir
   * 5. Restart cluster with old shared name and edits dir, but only latest
   *    edits dir. This should fail since we dont have latest name dir
   */
  public void testNameEditsConfigsFailure() throws IOException {
    Path file1 = new Path("TestNameEditsConfigs1");
    Path file2 = new Path("TestNameEditsConfigs2");
    Path file3 = new Path("TestNameEditsConfigs3");
    MiniDFSCluster cluster = null;
    Configuration conf = null;
    FileSystem fileSys = null;
    File base_dir = new File(System.getProperty("test.build.data"), "dfs/");
    File newNameDir = new File(base_dir, "name");
    File newEditsDir = new File(base_dir, "edits");
    File nameAndEdits = new File(base_dir, "name_and_edits");
    
    // Start namenode with same dfs.name.dir and dfs.name.edits.dir
    conf = new Configuration();
    conf.set("dfs.name.dir", nameAndEdits.getPath());
    conf.set("dfs.name.edits.dir", nameAndEdits.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    // Manage our own dfs directories
    cluster = new MiniDFSCluster(0, conf, numDatanodes, true, false, true, null,
                                  null, null, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(!fileSys.exists(file1));
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally  {
      fileSys.close();
      cluster.shutdown();
    }

    // Start namenode with additional dfs.name.dir and dfs.name.edits.dir
    conf =  new Configuration();
    assertTrue(newNameDir.mkdir());
    assertTrue(newEditsDir.mkdir());

    conf.set("dfs.name.dir", nameAndEdits.getPath() +
              "," + newNameDir.getPath());
    conf.set("dfs.name.edits.dir", nameAndEdits.getPath() +
              "," + newEditsDir.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    // Manage our own dfs directories. Do not format.
    cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true, 
                                  null, null, null, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(fileSys.exists(file1));
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      writeFile(fileSys, file2, replication);
      checkFile(fileSys, file2, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
    
    // Now remove common directory both have and start namenode with 
    // separate name and edits dirs
    conf =  new Configuration();
    conf.set("dfs.name.dir", newNameDir.getPath());
    conf.set("dfs.name.edits.dir", newEditsDir.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true,
                                  null, null, null, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();

    try {
      assertTrue(!fileSys.exists(file1));
      assertTrue(fileSys.exists(file2));
      checkFile(fileSys, file2, replication);
      cleanupFile(fileSys, file2);
      writeFile(fileSys, file3, replication);
      checkFile(fileSys, file3, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    // Add old shared directory for name and edits along with latest name
    conf = new Configuration();
    conf.set("dfs.name.dir", newNameDir.getPath() + "," + 
             nameAndEdits.getPath());
    conf.set("dfs.name.edits.dir", nameAndEdits.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    try {
      cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true,
                                  null, null, null, null);
      assertTrue(false);
    } catch (IOException e) { // expect to fail
      System.out.println("cluster start failed due to missing " +
                         "latest edits dir");
    } finally {
      cluster = null;
    }

    // Add old shared directory for name and edits along with latest edits
    conf = new Configuration();
    conf.set("dfs.name.dir", nameAndEdits.getPath());
    conf.set("dfs.name.edits.dir", newEditsDir.getPath() +
             "," + nameAndEdits.getPath());
    replication = (short)conf.getInt("dfs.replication", 3);
    try {
      cluster = new MiniDFSCluster(0, conf, numDatanodes, false, false, true,
                                   null, null, null, null);
      assertTrue(false);
    } catch (IOException e) { // expect to fail
      System.out.println("cluster start failed due to missing latest name dir");
    } finally {
      cluster = null;
    }

    // Cleanup
    if (!FileUtil.fullyDelete(newNameDir)) 
      throw new IOException("Cannot remove directory " + newNameDir);
    if (!FileUtil.fullyDelete(newEditsDir)) 
      throw new IOException("Cannot remove directory " + newEditsDir);
    if (!FileUtil.fullyDelete(nameAndEdits))
      throw new IOException("Cannot remove directory " + nameAndEdits);
  }
}
