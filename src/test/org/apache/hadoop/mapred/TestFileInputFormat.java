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
package org.apache.hadoop.mapred;

import java.io.DataOutputStream;
import java.io.IOException;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.io.Text;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class TestFileInputFormat extends TestCase {

  public void testLocality() throws Exception {
    JobConf conf = new JobConf();
    MiniDFSCluster dfs = null;
    try {
      dfs = new MiniDFSCluster(conf, 4, true,
                               new String[]{"/rack0", "/rack0", 
                                             "/rack1", "/rack1"},
                               new String[]{"host0", "host1", 
                                            "host2", "host3"});
      FileSystem fs = dfs.getFileSystem();
      System.out.println("FileSystem " + fs.getUri());
      Path path = new Path("/foo/bar");
      // create a multi-block file on hdfs
      DataOutputStream out = fs.create(path, true, 4096, 
                                       (short) 2, 512, null);
      for(int i=0; i < 1000; ++i) {
        out.writeChars("Hello\n");
      }
      out.close();
      System.out.println("Wrote file");

      // split it using a file input format
      TextInputFormat.addInputPath(conf, path);
      TextInputFormat inFormat = new TextInputFormat();
      inFormat.configure(conf);
      InputSplit[] splits = inFormat.getSplits(conf, 1);
      FileStatus fileStatus = fs.getFileStatus(path);
      BlockLocation[] locations = 
        fs.getFileBlockLocations(fileStatus, 0, fileStatus.getLen());
      System.out.println("Made splits");

      // make sure that each split is a block and the locations match
      for(int i=0; i < splits.length; ++i) {
        FileSplit fileSplit = (FileSplit) splits[i];
        System.out.println("File split: " + fileSplit);
        for (String h: fileSplit.getLocations()) {
          System.out.println("Location: " + h);
        }
        System.out.println("Block: " + locations[i]);
        assertEquals(locations[i].getOffset(), fileSplit.getStart());
        assertEquals(locations[i].getLength(), fileSplit.getLength());
        String[] blockLocs = locations[i].getHosts();
        String[] splitLocs = fileSplit.getLocations();
        assertEquals(2, blockLocs.length);
        assertEquals(2, splitLocs.length);
        assertTrue((blockLocs[0].equals(splitLocs[0]) && 
                    blockLocs[1].equals(splitLocs[1])) ||
                   (blockLocs[1].equals(splitLocs[0]) &&
                    blockLocs[0].equals(splitLocs[1])));
      }
    } finally {
      if (dfs != null) {
        dfs.shutdown();
      }
    }
  }

  
  final Path root = new Path("/TestFileInputFormat");
  final Path file1 = new Path(root, "file1");
  final Path dir1 = new Path(root, "dir1");
  final Path file2 = new Path(dir1, "file2");

  static final int BLOCKSIZE = 1024;
  static final byte[] databuf = new byte[BLOCKSIZE];

  private static final Log LOG = LogFactory.getLog(TestFileInputFormat.class);
  
  private static final String rack1[] = new String[] {
    "/r1"
  };
  private static final String hosts1[] = new String[] {
    "host1.rack1.com"
  };
  
  /** Dummy class to extend CombineFileInputFormat*/
  private class DummyFileInputFormat extends FileInputFormat<Text, Text> {
    @Override
    public RecordReader<Text, Text> getRecordReader(InputSplit split,
        JobConf job, Reporter reporter) throws IOException {
      return null;
    }
  }

  public void testMultiLevelInput() throws IOException {
    String namenode = null;
    MiniDFSCluster dfs = null;
    FileSystem fileSys = null;
    try {
      JobConf conf = new JobConf();
      
      conf.setBoolean("dfs.replication.considerLoad", false);
      dfs = new MiniDFSCluster(conf, 1, true, rack1, hosts1);
      dfs.waitActive();

      namenode = (dfs.getFileSystem()).getUri().getHost() + ":" +
                 (dfs.getFileSystem()).getUri().getPort();

      fileSys = dfs.getFileSystem();
      if (!fileSys.mkdirs(dir1)) {
        throw new IOException("Mkdirs failed to create " + root.toString());
      }
      writeFile(conf, file1, (short)1, 1);
      writeFile(conf, file2, (short)1, 1);

      // split it using a CombinedFile input format
      DummyFileInputFormat inFormat = new DummyFileInputFormat();
      inFormat.setInputPaths(conf, root);

      // By default, we don't allow multi-level/recursive inputs
      boolean exceptionThrown = false;
      try {
        InputSplit[] splits = inFormat.getSplits(conf, 1);
      } catch (Exception e) {
        exceptionThrown = true;
      }
      assertTrue("Exception should be thrown by default for scanning a "
          + "directory with directories inside.", exceptionThrown);

      // Enable multi-level/recursive inputs
      conf.setBoolean("mapred.input.dir.recursive", true);
      InputSplit[] splits = inFormat.getSplits(conf, 1);
      assertEquals(splits.length, 2);
      
    } finally {
      if (dfs != null) {
        dfs.shutdown();
      }
    }
  }

  static void writeFile(Configuration conf, Path name,
      short replication, int numBlocks) throws IOException {
    FileSystem fileSys = FileSystem.get(conf);

    FSDataOutputStream stm = fileSys.create(name, true,
                                            conf.getInt("io.file.buffer.size", 4096),
                                            replication, (long)BLOCKSIZE);
    for (int i = 0; i < numBlocks; i++) {
      stm.write(databuf);
    }
    stm.close();
    DFSTestUtil.waitReplication(fileSys, name, replication);
  }

  
}
