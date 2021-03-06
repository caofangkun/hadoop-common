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
package org.apache.hadoop.hdfs.server.datanode;

import static org.apache.hadoop.hdfs.server.datanode.DataNode.DN_CLIENTTRACE_FORMAT;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.zip.Checksum;

import org.apache.commons.logging.Log;
import org.apache.hadoop.fs.FSOutputSummer;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.datatransfer.BlockConstructionStage;
import org.apache.hadoop.hdfs.protocol.datatransfer.PacketHeader;
import org.apache.hadoop.hdfs.protocol.datatransfer.PipelineAck;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.Status;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.ReplicaInputStreams;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.ReplicaOutputStreams;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.util.DataTransferThrottler;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.DataChecksum;

/** A class that receives a block and writes to its own disk, meanwhile
 * may copies it to another site. If a throttler is provided,
 * streaming throttling is also supported.
 **/
class BlockReceiver implements Closeable {
  public static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;

  private static final long CACHE_DROP_LAG_BYTES = 8 * 1024 * 1024;
  
  private DataInputStream in = null; // from where data are read
  private DataChecksum clientChecksum; // checksum used by client
  private DataChecksum diskChecksum; // checksum we write to disk
  
  /**
   * In the case that the client is writing with a different
   * checksum polynomial than the block is stored with on disk,
   * the DataNode needs to recalculate checksums before writing.
   */
  private boolean needsChecksumTranslation;
  private OutputStream out = null; // to block file at local disk
  private FileDescriptor outFd;
  private OutputStream cout = null; // output stream for cehcksum file
  private DataOutputStream checksumOut = null; // to crc file at local disk
  private int bytesPerChecksum;
  private int checksumSize;
  private ByteBuffer buf; // contains one full packet.
  private int bufRead; //amount of valid data in the buf
  private int maxPacketReadLen;
  protected final String inAddr;
  protected final String myAddr;
  private String mirrorAddr;
  private DataOutputStream mirrorOut;
  private Daemon responder = null;
  private DataTransferThrottler throttler;
  private ReplicaOutputStreams streams;
  private DatanodeInfo srcDataNode = null;
  private Checksum partialCrc = null;
  private final DataNode datanode;
  volatile private boolean mirrorError;

  // Cache management state
  private boolean dropCacheBehindWrites;
  private boolean syncBehindWrites;
  private long lastCacheDropOffset = 0;

  /** The client name.  It is empty if a datanode is the client */
  private final String clientname;
  private final boolean isClient; 
  private final boolean isDatanode;

  /** the block to receive */
  private final ExtendedBlock block; 
  /** the replica to write */
  private final ReplicaInPipelineInterface replicaInfo;
  /** pipeline stage */
  private final BlockConstructionStage stage;
  private final boolean isTransfer;

  private boolean syncOnClose;

  BlockReceiver(final ExtendedBlock block, final DataInputStream in,
      final String inAddr, final String myAddr,
      final BlockConstructionStage stage, 
      final long newGs, final long minBytesRcvd, final long maxBytesRcvd, 
      final String clientname, final DatanodeInfo srcDataNode,
      final DataNode datanode, DataChecksum requestedChecksum)
      throws IOException {
    try{
      this.block = block;
      this.in = in;
      this.inAddr = inAddr;
      this.myAddr = myAddr;
      this.srcDataNode = srcDataNode;
      this.datanode = datanode;

      this.clientname = clientname;
      this.isDatanode = clientname.length() == 0;
      this.isClient = !this.isDatanode;

      //for datanode, we have
      //1: clientName.length() == 0, and
      //2: stage == null or PIPELINE_SETUP_CREATE
      this.stage = stage;
      this.isTransfer = stage == BlockConstructionStage.TRANSFER_RBW
          || stage == BlockConstructionStage.TRANSFER_FINALIZED;

      if (LOG.isDebugEnabled()) {
        LOG.debug(getClass().getSimpleName() + ": " + block
            + "\n  isClient  =" + isClient + ", clientname=" + clientname
            + "\n  isDatanode=" + isDatanode + ", srcDataNode=" + srcDataNode
            + "\n  inAddr=" + inAddr + ", myAddr=" + myAddr
            );
      }

      //
      // Open local disk out
      //
      if (isDatanode) { //replication or move
        replicaInfo = datanode.data.createTemporary(block);
      } else {
        switch (stage) {
        case PIPELINE_SETUP_CREATE:
          replicaInfo = datanode.data.createRbw(block);
          datanode.notifyNamenodeReceivingBlock(block);
          break;
        case PIPELINE_SETUP_STREAMING_RECOVERY:
          replicaInfo = datanode.data.recoverRbw(
              block, newGs, minBytesRcvd, maxBytesRcvd);
          block.setGenerationStamp(newGs);
          break;
        case PIPELINE_SETUP_APPEND:
          replicaInfo = datanode.data.append(block, newGs, minBytesRcvd);
          if (datanode.blockScanner != null) { // remove from block scanner
            datanode.blockScanner.deleteBlock(block.getBlockPoolId(),
                block.getLocalBlock());
          }
          block.setGenerationStamp(newGs);
          datanode.notifyNamenodeReceivingBlock(block);
          break;
        case PIPELINE_SETUP_APPEND_RECOVERY:
          replicaInfo = datanode.data.recoverAppend(block, newGs, minBytesRcvd);
          if (datanode.blockScanner != null) { // remove from block scanner
            datanode.blockScanner.deleteBlock(block.getBlockPoolId(),
                block.getLocalBlock());
          }
          block.setGenerationStamp(newGs);
          datanode.notifyNamenodeReceivingBlock(block);
          break;
        case TRANSFER_RBW:
        case TRANSFER_FINALIZED:
          // this is a transfer destination
          replicaInfo = datanode.data.createTemporary(block);
          break;
        default: throw new IOException("Unsupported stage " + stage + 
              " while receiving block " + block + " from " + inAddr);
        }
      }
      this.dropCacheBehindWrites = datanode.getDnConf().dropCacheBehindWrites;
      this.syncBehindWrites = datanode.getDnConf().syncBehindWrites;
      
      final boolean isCreate = isDatanode || isTransfer 
          || stage == BlockConstructionStage.PIPELINE_SETUP_CREATE;
      streams = replicaInfo.createStreams(isCreate, requestedChecksum);
      assert streams != null : "null streams!";

      // read checksum meta information
      this.clientChecksum = requestedChecksum;
      this.diskChecksum = streams.getChecksum();
      this.needsChecksumTranslation = !clientChecksum.equals(diskChecksum);
      this.bytesPerChecksum = diskChecksum.getBytesPerChecksum();
      this.checksumSize = diskChecksum.getChecksumSize();

      this.out = streams.getDataOut();
      if (out instanceof FileOutputStream) {
        this.outFd = ((FileOutputStream)out).getFD();
      } else {
        LOG.warn("Could not get file descriptor for outputstream of class " +
            out.getClass());
      }
      this.cout = streams.getChecksumOut();
      this.checksumOut = new DataOutputStream(new BufferedOutputStream(
          cout, HdfsConstants.SMALL_BUFFER_SIZE));
      // write data chunk header if creating a new replica
      if (isCreate) {
        BlockMetadataHeader.writeHeader(checksumOut, diskChecksum);
      } 
    } catch (ReplicaAlreadyExistsException bae) {
      throw bae;
    } catch (ReplicaNotFoundException bne) {
      throw bne;
    } catch(IOException ioe) {
      IOUtils.closeStream(this);
      cleanupBlock();
      
      // check if there is a disk error
      IOException cause = DatanodeUtil.getCauseIfDiskError(ioe);
      DataNode.LOG.warn("IOException in BlockReceiver constructor. Cause is ",
          cause);
      
      if (cause != null) { // possible disk error
        ioe = cause;
        datanode.checkDiskError(ioe); // may throw an exception here
      }
      
      throw ioe;
    }
  }

  /** Return the datanode object. */
  DataNode getDataNode() {return datanode;}

  /**
   * close files.
   */
  @Override
  public void close() throws IOException {
    IOException ioe = null;
    if (syncOnClose && (out != null || checksumOut != null)) {
      datanode.metrics.incrFsyncCount();      
    }
    long flushTotalNanos = 0;
    boolean measuredFlushTime = false;
    // close checksum file
    try {
      if (checksumOut != null) {
        long flushStartNanos = System.nanoTime();
        checksumOut.flush();
        long flushEndNanos = System.nanoTime();
        if (syncOnClose && (cout instanceof FileOutputStream)) {
          long fsyncStartNanos = flushEndNanos;
          ((FileOutputStream)cout).getChannel().force(true);
          datanode.metrics.addFsyncNanos(System.nanoTime() - fsyncStartNanos);
        }
        flushTotalNanos += flushEndNanos - flushStartNanos;
        measuredFlushTime = true;
        checksumOut.close();
        checksumOut = null;
      }
    } catch(IOException e) {
      ioe = e;
    }
    finally {
      IOUtils.closeStream(checksumOut);
    }
    // close block file
    try {
      if (out != null) {
        long flushStartNanos = System.nanoTime();
        out.flush();
        long flushEndNanos = System.nanoTime();
        if (syncOnClose && (out instanceof FileOutputStream)) {
          long fsyncStartNanos = flushEndNanos;
          ((FileOutputStream)out).getChannel().force(true);
          datanode.metrics.addFsyncNanos(System.nanoTime() - fsyncStartNanos);
        }
        flushTotalNanos += flushEndNanos - flushStartNanos;
        measuredFlushTime = true;
        out.close();
        out = null;
      }
    } catch (IOException e) {
      ioe = e;
    }
    finally{
      IOUtils.closeStream(out);
    }
    if (measuredFlushTime) {
      datanode.metrics.addFlushNanos(flushTotalNanos);
    }
    // disk check
    if(ioe != null) {
      datanode.checkDiskError(ioe);
      throw ioe;
    }
  }

  /**
   * Flush block data and metadata files to disk.
   * @throws IOException
   */
  void flushOrSync(boolean isSync) throws IOException {
    if (isSync && (out != null || checksumOut != null)) {
      datanode.metrics.incrFsyncCount();      
    }
    long flushTotalNanos = 0;
    if (checksumOut != null) {
      long flushStartNanos = System.nanoTime();
      checksumOut.flush();
      long flushEndNanos = System.nanoTime();
      if (isSync && (cout instanceof FileOutputStream)) {
        long fsyncStartNanos = flushEndNanos;
        ((FileOutputStream)cout).getChannel().force(true);
        datanode.metrics.addFsyncNanos(System.nanoTime() - fsyncStartNanos);
      }
      flushTotalNanos += flushEndNanos - flushStartNanos;
    }
    if (out != null) {
      long flushStartNanos = System.nanoTime();
      out.flush();
      long flushEndNanos = System.nanoTime();
      if (isSync && (out instanceof FileOutputStream)) {
        long fsyncStartNanos = flushEndNanos;
        ((FileOutputStream)out).getChannel().force(true);
        datanode.metrics.addFsyncNanos(System.nanoTime() - fsyncStartNanos);
      }
      flushTotalNanos += flushEndNanos - flushStartNanos;
    }
    if (checksumOut != null || out != null) {
      datanode.metrics.addFlushNanos(flushTotalNanos);
    }
  }

  /**
   * While writing to mirrorOut, failure to write to mirror should not
   * affect this datanode unless it is caused by interruption.
   */
  private void handleMirrorOutError(IOException ioe) throws IOException {
    String bpid = block.getBlockPoolId();
    LOG.info(datanode.getDNRegistrationForBP(bpid)
        + ":Exception writing block " + block + " to mirror " + mirrorAddr, ioe);
    if (Thread.interrupted()) { // shut down if the thread is interrupted
      throw ioe;
    } else { // encounter an error while writing to mirror
      // continue to run even if can not write to mirror
      // notify client of the error
      // and wait for the client to shut down the pipeline
      mirrorError = true;
    }
  }
  
  /**
   * Verify multiple CRC chunks. 
   */
  private void verifyChunks( byte[] dataBuf, int dataOff, int len, 
                             byte[] checksumBuf, int checksumOff ) 
                             throws IOException {
    while (len > 0) {
      int chunkLen = Math.min(len, bytesPerChecksum);
      
      clientChecksum.update(dataBuf, dataOff, chunkLen);

      if (!clientChecksum.compare(checksumBuf, checksumOff)) {
        if (srcDataNode != null) {
          try {
            LOG.info("report corrupt block " + block + " from datanode " +
                      srcDataNode + " to namenode");
            datanode.reportRemoteBadBlock(srcDataNode, block);
          } catch (IOException e) {
            LOG.warn("Failed to report bad block " + block + 
                      " from datanode " + srcDataNode + " to namenode");
          }
        }
        throw new IOException("Unexpected checksum mismatch " + 
                              "while writing " + block + " from " + inAddr);
      }

      clientChecksum.reset();
      dataOff += chunkLen;
      checksumOff += checksumSize;
      len -= chunkLen;
    }
  }
  
    
  /**
   * Translate CRC chunks from the client's checksum implementation
   * to the disk checksum implementation.
   * 
   * This does not verify the original checksums, under the assumption
   * that they have already been validated.
   */
  private void translateChunks( byte[] dataBuf, int dataOff, int len,
      byte[] checksumBuf, int checksumOff ) {
    if (len == 0) return;
    
    int numChunks = (len - 1)/bytesPerChecksum + 1;
    
    diskChecksum.calculateChunkedSums(
        ByteBuffer.wrap(dataBuf, dataOff, len),
        ByteBuffer.wrap(checksumBuf, checksumOff, numChunks * checksumSize));
  }

  /**
   * Makes sure buf.position() is zero without modifying buf.remaining().
   * It moves the data if position needs to be changed.
   */
  private void shiftBufData() {
    if (bufRead != buf.limit()) {
      throw new IllegalStateException("bufRead should be same as " +
                                      "buf.limit()");
    }
    
    //shift the remaining data on buf to the front
    if (buf.position() > 0) {
      int dataLeft = buf.remaining();
      if (dataLeft > 0) {
        byte[] b = buf.array();
        System.arraycopy(b, buf.position(), b, 0, dataLeft);
      }
      buf.position(0);
      bufRead = dataLeft;
      buf.limit(bufRead);
    }
  }
  
  /**
   * reads upto toRead byte to buf at buf.limit() and increments the limit.
   * throws an IOException if read does not succeed.
   */
  private int readToBuf(int toRead) throws IOException {
    if (toRead < 0) {
      toRead = (maxPacketReadLen > 0 ? maxPacketReadLen : buf.capacity())
               - buf.limit();
    }
    
    int nRead = in.read(buf.array(), buf.limit(), toRead);
    
    if (nRead < 0) {
      throw new EOFException("while trying to read " + toRead + " bytes");
    }
    bufRead = buf.limit() + nRead;
    buf.limit(bufRead);
    return nRead;
  }
  
  
  /**
   * Reads (at least) one packet and returns the packet length.
   * buf.position() points to the start of the packet and 
   * buf.limit() point to the end of the packet. There could 
   * be more data from next packet in buf.<br><br>
   * 
   * It tries to read a full packet with single read call.
   * Consecutive packets are usually of the same length.
   */
  private void readNextPacket() throws IOException {
    /* This dances around buf a little bit, mainly to read 
     * full packet with single read and to accept arbitrary size  
     * for next packet at the same time.
     */
    if (buf == null) {
      /* initialize buffer to the best guess size:
       * 'chunksPerPacket' calculation here should match the same 
       * calculation in DFSClient to make the guess accurate.
       */
      int chunkSize = bytesPerChecksum + checksumSize;
      int chunksPerPacket = (datanode.getDnConf().writePacketSize - PacketHeader.PKT_HEADER_LEN
                             + chunkSize - 1)/chunkSize;
      buf = ByteBuffer.allocate(PacketHeader.PKT_HEADER_LEN +
                                Math.max(chunksPerPacket, 1) * chunkSize);
      buf.limit(0);
    }
    
    // See if there is data left in the buffer :
    if (bufRead > buf.limit()) {
      buf.limit(bufRead);
    }
    
    while (buf.remaining() < HdfsConstants.BYTES_IN_INTEGER) {
      if (buf.position() > 0) {
        shiftBufData();
      }
      readToBuf(-1);
    }
    
    /* We mostly have the full packet or at least enough for an int
     */
    buf.mark();
    int payloadLen = buf.getInt();
    buf.reset();
    
    // check corrupt values for pktLen, 100MB upper limit should be ok?
    if (payloadLen < 0 || payloadLen > (100*1024*1024)) {
      throw new IOException("Incorrect value for packet payload : " +
                            payloadLen);
    }
    
    // Subtract BYTES_IN_INTEGER since that accounts for the payloadLen that
    // we read above.
    int pktSize = payloadLen + PacketHeader.PKT_HEADER_LEN
        - HdfsConstants.BYTES_IN_INTEGER;
    
    if (buf.remaining() < pktSize) {
      //we need to read more data
      int toRead = pktSize - buf.remaining();
      
      // first make sure buf has enough space.        
      int spaceLeft = buf.capacity() - buf.limit();
      if (toRead > spaceLeft && buf.position() > 0) {
        shiftBufData();
        spaceLeft = buf.capacity() - buf.limit();
      }
      if (toRead > spaceLeft) {
        byte oldBuf[] = buf.array();
        int toCopy = buf.limit();
        buf = ByteBuffer.allocate(toCopy + toRead);
        System.arraycopy(oldBuf, 0, buf.array(), 0, toCopy);
        buf.limit(toCopy);
      }
      
      //now read:
      while (toRead > 0) {
        toRead -= readToBuf(toRead);
      }
    }
    
    if (buf.remaining() > pktSize) {
      buf.limit(buf.position() + pktSize);
    }
    
    if (pktSize > maxPacketReadLen) {
      maxPacketReadLen = pktSize;
    }
  }
  
  /** 
   * Receives and processes a packet. It can contain many chunks.
   * returns the number of data bytes that the packet has.
   */
  private int receivePacket() throws IOException {
    // read the next packet
    readNextPacket();

    buf.mark();
    PacketHeader header = new PacketHeader();
    header.readFields(buf);
    int endOfHeader = buf.position();
    buf.reset();

    // Sanity check the header
    if (header.getOffsetInBlock() > replicaInfo.getNumBytes()) {
      throw new IOException("Received an out-of-sequence packet for " + block + 
          "from " + inAddr + " at offset " + header.getOffsetInBlock() +
          ". Expecting packet starting at " + replicaInfo.getNumBytes());
    }
    if (header.getDataLen() < 0) {
      throw new IOException("Got wrong length during writeBlock(" + block + 
                            ") from " + inAddr + " at offset " + 
                            header.getOffsetInBlock() + ": " +
                            header.getDataLen()); 
    }

    return receivePacket(
      header.getOffsetInBlock(),
      header.getSeqno(),
      header.isLastPacketInBlock(),
      header.getDataLen(),
      header.getSyncBlock(),
      endOfHeader);
  }

  /**
   * Write the received packet to disk (data only)
   */
  private void writePacketToDisk(byte[] pktBuf, int startByteToDisk, 
      int numBytesToDisk) throws IOException {
    out.write(pktBuf, startByteToDisk, numBytesToDisk);
  }
  
  /** 
   * Receives and processes a packet. It can contain many chunks.
   * returns the number of data bytes that the packet has.
   */
  private int receivePacket(long offsetInBlock, long seqno,
      boolean lastPacketInBlock, int len, boolean syncBlock,
      int endOfHeader) throws IOException {
    if (LOG.isDebugEnabled()){
      LOG.debug("Receiving one packet for block " + block +
                " of length " + len +
                " seqno " + seqno +
                " offsetInBlock " + offsetInBlock +
                " syncBlock " + syncBlock +
                " lastPacketInBlock " + lastPacketInBlock);
    }
    // make sure the block gets sync'ed upon close
    this.syncOnClose |= syncBlock && lastPacketInBlock;

    // update received bytes
    long firstByteInBlock = offsetInBlock;
    offsetInBlock += len;
    if (replicaInfo.getNumBytes() < offsetInBlock) {
      replicaInfo.setNumBytes(offsetInBlock);
    }
    
    // put in queue for pending acks
    if (responder != null) {
      ((PacketResponder)responder.getRunnable()).enqueue(seqno,
                                      lastPacketInBlock, offsetInBlock); 
    }  

    //First write the packet to the mirror:
    if (mirrorOut != null && !mirrorError) {
      try {
        mirrorOut.write(buf.array(), buf.position(), buf.remaining());
        mirrorOut.flush();
      } catch (IOException e) {
        handleMirrorOutError(e);
      }
    }
    
    buf.position(endOfHeader);        
    
    if (lastPacketInBlock || len == 0) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Receiving an empty packet or the end of the block " + block);
      }
      // flush unless close() would flush anyway
      if (syncBlock && !lastPacketInBlock) {
        flushOrSync(true);
      }
    } else {
      int checksumLen = ((len + bytesPerChecksum - 1)/bytesPerChecksum)*
                                                            checksumSize;

      if ( buf.remaining() != (checksumLen + len)) {
        throw new IOException("Data remaining in packet does not match" +
                              "sum of checksumLen and dataLen " +
                              " size remaining: " + buf.remaining() +
                              " data len: " + len +
                              " checksum Len: " + checksumLen);
      }
      int checksumOff = buf.position();
      int dataOff = checksumOff + checksumLen;
      byte pktBuf[] = buf.array();

      buf.position(buf.limit()); // move to the end of the data.

      /* skip verifying checksum iff this is not the last one in the 
       * pipeline and clientName is non-null. i.e. Checksum is verified
       * on all the datanodes when the data is being written by a 
       * datanode rather than a client. Whe client is writing the data, 
       * protocol includes acks and only the last datanode needs to verify 
       * checksum.
       */
      if (mirrorOut == null || isDatanode || needsChecksumTranslation) {
        verifyChunks(pktBuf, dataOff, len, pktBuf, checksumOff);
        if (needsChecksumTranslation) {
          // overwrite the checksums in the packet buffer with the
          // appropriate polynomial for the disk storage.
          translateChunks(pktBuf, dataOff, len, pktBuf, checksumOff);
        }
      }
      
      // by this point, the data in the buffer uses the disk checksum

      byte[] lastChunkChecksum;
      
      try {
        long onDiskLen = replicaInfo.getBytesOnDisk();
        if (onDiskLen<offsetInBlock) {
          //finally write to the disk :
          
          if (onDiskLen % bytesPerChecksum != 0) { 
            // prepare to overwrite last checksum
            adjustCrcFilePosition();
          }
          
          // If this is a partial chunk, then read in pre-existing checksum
          if (firstByteInBlock % bytesPerChecksum != 0) {
            LOG.info("Packet starts at " + firstByteInBlock +
                     " for block " + block +
                     " which is not a multiple of bytesPerChecksum " +
                     bytesPerChecksum);
            long offsetInChecksum = BlockMetadataHeader.getHeaderSize() +
                onDiskLen / bytesPerChecksum * checksumSize;
            computePartialChunkCrc(onDiskLen, offsetInChecksum, bytesPerChecksum);
          }

          int startByteToDisk = dataOff+(int)(onDiskLen-firstByteInBlock);
          int numBytesToDisk = (int)(offsetInBlock-onDiskLen);
          writePacketToDisk(pktBuf, startByteToDisk, numBytesToDisk);

          // If this is a partial chunk, then verify that this is the only
          // chunk in the packet. Calculate new crc for this chunk.
          if (partialCrc != null) {
            if (len > bytesPerChecksum) {
              throw new IOException("Got wrong length during writeBlock(" + 
                                    block + ") from " + inAddr + " " +
                                    "A packet can have only one partial chunk."+
                                    " len = " + len + 
                                    " bytesPerChecksum " + bytesPerChecksum);
            }
            partialCrc.update(pktBuf, startByteToDisk, numBytesToDisk);
            byte[] buf = FSOutputSummer.convertToByteStream(partialCrc, checksumSize);
            lastChunkChecksum = Arrays.copyOfRange(
              buf, buf.length - checksumSize, buf.length
            );
            checksumOut.write(buf);
            if(LOG.isDebugEnabled()) {
              LOG.debug("Writing out partial crc for data len " + len);
            }
            partialCrc = null;
          } else {
            lastChunkChecksum = Arrays.copyOfRange(
              pktBuf, 
              checksumOff + checksumLen - checksumSize, 
              checksumOff + checksumLen
            );
            checksumOut.write(pktBuf, checksumOff, checksumLen);
          }
          /// flush entire packet, sync unless close() will sync
          flushOrSync(syncBlock && !lastPacketInBlock);
          
          replicaInfo.setLastChecksumAndDataLen(
            offsetInBlock, lastChunkChecksum
          );

          datanode.metrics.incrBytesWritten(len);

          dropOsCacheBehindWriter(offsetInBlock);
        }
      } catch (IOException iex) {
        datanode.checkDiskError(iex);
        throw iex;
      }
    }

    if (throttler != null) { // throttle I/O
      throttler.throttle(len);
    }
    
    return lastPacketInBlock?-1:len;
  }

  private void dropOsCacheBehindWriter(long offsetInBlock) {
    try {
      if (outFd != null &&
          offsetInBlock > lastCacheDropOffset + CACHE_DROP_LAG_BYTES) {
        long twoWindowsAgo = lastCacheDropOffset - CACHE_DROP_LAG_BYTES;
        if (twoWindowsAgo > 0 && dropCacheBehindWrites) {
          NativeIO.posixFadviseIfPossible(outFd, 0, lastCacheDropOffset,
              NativeIO.POSIX_FADV_DONTNEED);
        }
        
        if (syncBehindWrites) {
          NativeIO.syncFileRangeIfPossible(outFd, lastCacheDropOffset, CACHE_DROP_LAG_BYTES,
              NativeIO.SYNC_FILE_RANGE_WRITE);
        }
        
        lastCacheDropOffset += CACHE_DROP_LAG_BYTES;
      }
    } catch (Throwable t) {
      LOG.warn("Couldn't drop os cache behind writer for " + block, t);
    }
  }

  void receiveBlock(
      DataOutputStream mirrOut, // output to next datanode
      DataInputStream mirrIn,   // input from next datanode
      DataOutputStream replyOut,  // output to previous datanode
      String mirrAddr, DataTransferThrottler throttlerArg,
      DatanodeInfo[] downstreams) throws IOException {

      syncOnClose = datanode.getDnConf().syncOnClose;
      boolean responderClosed = false;
      mirrorOut = mirrOut;
      mirrorAddr = mirrAddr;
      throttler = throttlerArg;

    try {
      if (isClient && !isTransfer) {
        responder = new Daemon(datanode.threadGroup, 
            new PacketResponder(replyOut, mirrIn, downstreams));
        responder.start(); // start thread to processes responses
      }

      /* 
       * Receive until the last packet.
       */
      while (receivePacket() >= 0) {}

      // wait for all outstanding packet responses. And then
      // indicate responder to gracefully shutdown.
      // Mark that responder has been closed for future processing
      if (responder != null) {
        ((PacketResponder)responder.getRunnable()).close();
        responderClosed = true;
      }

      // If this write is for a replication or transfer-RBW/Finalized,
      // then finalize block or convert temporary to RBW.
      // For client-writes, the block is finalized in the PacketResponder.
      if (isDatanode || isTransfer) {
        // close the block/crc files
        close();
        block.setNumBytes(replicaInfo.getNumBytes());

        if (stage == BlockConstructionStage.TRANSFER_RBW) {
          // for TRANSFER_RBW, convert temporary to RBW
          datanode.data.convertTemporaryToRbw(block);
        } else {
          // for isDatnode or TRANSFER_FINALIZED
          // Finalize the block.
          datanode.data.finalizeBlock(block);
        }
        datanode.metrics.incrBlocksWritten();
      }

    } catch (IOException ioe) {
      LOG.info("Exception in receiveBlock for " + block, ioe);
      throw ioe;
    } finally {
      if (!responderClosed) { // Abnormal termination of the flow above
        IOUtils.closeStream(this);
        if (responder != null) {
          responder.interrupt();
        }
        cleanupBlock();
      }
      if (responder != null) {
        try {
          responder.join();
        } catch (InterruptedException e) {
          responder.interrupt();
          throw new IOException("Interrupted receiveBlock");
        }
        responder = null;
      }
    }
  }

  /** Cleanup a partial block 
   * if this write is for a replication request (and not from a client)
   */
  private void cleanupBlock() throws IOException {
    if (isDatanode) {
      datanode.data.unfinalizeBlock(block);
    }
  }

  /**
   * Adjust the file pointer in the local meta file so that the last checksum
   * will be overwritten.
   */
  private void adjustCrcFilePosition() throws IOException {
    if (out != null) {
     out.flush();
    }
    if (checksumOut != null) {
      checksumOut.flush();
    }

    // rollback the position of the meta file
    datanode.data.adjustCrcChannelPosition(block, streams, checksumSize);
  }

  /**
   * Convert a checksum byte array to a long
   */
  static private long checksum2long(byte[] checksum) {
    long crc = 0L;
    for(int i=0; i<checksum.length; i++) {
      crc |= (0xffL&(long)checksum[i])<<((checksum.length-i-1)*8);
    }
    return crc;
  }

  /**
   * reads in the partial crc chunk and computes checksum
   * of pre-existing data in partial chunk.
   */
  private void computePartialChunkCrc(long blkoff, long ckoff, 
                                      int bytesPerChecksum) throws IOException {

    // find offset of the beginning of partial chunk.
    //
    int sizePartialChunk = (int) (blkoff % bytesPerChecksum);
    int checksumSize = diskChecksum.getChecksumSize();
    blkoff = blkoff - sizePartialChunk;
    LOG.info("computePartialChunkCrc sizePartialChunk " + 
              sizePartialChunk +
              " block " + block +
              " offset in block " + blkoff +
              " offset in metafile " + ckoff);

    // create an input stream from the block file
    // and read in partial crc chunk into temporary buffer
    //
    byte[] buf = new byte[sizePartialChunk];
    byte[] crcbuf = new byte[checksumSize];
    ReplicaInputStreams instr = null;
    try { 
      instr = datanode.data.getTmpInputStreams(block, blkoff, ckoff);
      IOUtils.readFully(instr.getDataIn(), buf, 0, sizePartialChunk);

      // open meta file and read in crc value computer earlier
      IOUtils.readFully(instr.getChecksumIn(), crcbuf, 0, crcbuf.length);
    } finally {
      IOUtils.closeStream(instr);
    }

    // compute crc of partial chunk from data read in the block file.
    partialCrc = DataChecksum.newDataChecksum(
        diskChecksum.getChecksumType(), diskChecksum.getBytesPerChecksum());
    partialCrc.update(buf, 0, sizePartialChunk);
    LOG.info("Read in partial CRC chunk from disk for block " + block);

    // paranoia! verify that the pre-computed crc matches what we
    // recalculated just now
    if (partialCrc.getValue() != checksum2long(crcbuf)) {
      String msg = "Partial CRC " + partialCrc.getValue() +
                   " does not match value computed the " +
                   " last time file was closed " +
                   checksum2long(crcbuf);
      throw new IOException(msg);
    }
  }
  
  private static enum PacketResponderType {
    NON_PIPELINE, LAST_IN_PIPELINE, HAS_DOWNSTREAM_IN_PIPELINE
  }
  
  /**
   * Processed responses from downstream datanodes in the pipeline
   * and sends back replies to the originator.
   */
  class PacketResponder implements Runnable, Closeable {   

    /** queue for packets waiting for ack */
    private final LinkedList<Packet> ackQueue = new LinkedList<Packet>(); 
    /** the thread that spawns this responder */
    private final Thread receiverThread = Thread.currentThread();
    /** is this responder running? */
    private volatile boolean running = true;

    /** input from the next downstream datanode */
    private final DataInputStream downstreamIn;
    /** output to upstream datanode/client */
    private final DataOutputStream upstreamOut;

    /** The type of this responder */
    private final PacketResponderType type;
    /** for log and error messages */
    private final String myString; 

    @Override
    public String toString() {
      return myString;
    }

    PacketResponder(final DataOutputStream upstreamOut,
        final DataInputStream downstreamIn,
        final DatanodeInfo[] downstreams) {
      this.downstreamIn = downstreamIn;
      this.upstreamOut = upstreamOut;

      this.type = downstreams == null? PacketResponderType.NON_PIPELINE
          : downstreams.length == 0? PacketResponderType.LAST_IN_PIPELINE
              : PacketResponderType.HAS_DOWNSTREAM_IN_PIPELINE;

      final StringBuilder b = new StringBuilder(getClass().getSimpleName())
          .append(": ").append(block).append(", type=").append(type);
      if (type != PacketResponderType.HAS_DOWNSTREAM_IN_PIPELINE) {
        b.append(", downstreams=").append(downstreams.length)
            .append(":").append(Arrays.asList(downstreams));
      }
      this.myString = b.toString();
    }

    /**
     * enqueue the seqno that is still be to acked by the downstream datanode.
     * @param seqno
     * @param lastPacketInBlock
     * @param offsetInBlock
     */
    synchronized void enqueue(final long seqno,
        final boolean lastPacketInBlock, final long offsetInBlock) {
      if (running) {
        final Packet p = new Packet(seqno, lastPacketInBlock, offsetInBlock,
            System.nanoTime());
        if(LOG.isDebugEnabled()) {
          LOG.debug(myString + ": enqueue " + p);
        }
        ackQueue.addLast(p);
        notifyAll();
      }
    }

    /**
     * wait for all pending packets to be acked. Then shutdown thread.
     */
    @Override
    public synchronized void close() {
      while (running && ackQueue.size() != 0 && datanode.shouldRun) {
        try {
          wait();
        } catch (InterruptedException e) {
          running = false;
          Thread.currentThread().interrupt();
        }
      }
      if(LOG.isDebugEnabled()) {
        LOG.debug(myString + ": closing");
      }
      running = false;
      notifyAll();
    }

    /**
     * Thread to process incoming acks.
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
      boolean lastPacketInBlock = false;
      final long startTime = ClientTraceLog.isInfoEnabled() ? System.nanoTime() : 0;
      while (running && datanode.shouldRun && !lastPacketInBlock) {

        long totalAckTimeNanos = 0;
        boolean isInterrupted = false;
        try {
            Packet pkt = null;
            long expected = -2;
            PipelineAck ack = new PipelineAck();
            long seqno = PipelineAck.UNKOWN_SEQNO;
            long ackRecvNanoTime = 0;
            try {
              if (type != PacketResponderType.LAST_IN_PIPELINE
                  && !mirrorError) {
                // read an ack from downstream datanode
                ack.readFields(downstreamIn);
                ackRecvNanoTime = System.nanoTime();
                if (LOG.isDebugEnabled()) {
                  LOG.debug(myString + " got " + ack);
                }
                seqno = ack.getSeqno();
              }
              if (seqno != PipelineAck.UNKOWN_SEQNO
                  || type == PacketResponderType.LAST_IN_PIPELINE) {
                synchronized (this) {
                  while (running && datanode.shouldRun && ackQueue.size() == 0) {
                    if (LOG.isDebugEnabled()) {
                      LOG.debug(myString + ": seqno=" + seqno +
                                " waiting for local datanode to finish write.");
                    }
                    wait();
                  }
                  if (!running || !datanode.shouldRun) {
                    break;
                  }
                  pkt = ackQueue.getFirst();
                  expected = pkt.seqno;
                  if (type == PacketResponderType.HAS_DOWNSTREAM_IN_PIPELINE
                      && seqno != expected) {
                    throw new IOException(myString + "seqno: expected="
                        + expected + ", received=" + seqno);
                  }
                  if (type == PacketResponderType.HAS_DOWNSTREAM_IN_PIPELINE) {
                    // The total ack time includes the ack times of downstream nodes.
                    // The value is 0 if this responder doesn't have a downstream
                    // DN in the pipeline.
                    totalAckTimeNanos = ackRecvNanoTime - pkt.ackEnqueueNanoTime;
                    // Report the elapsed time from ack send to ack receive minus
                    // the downstream ack time.
                    long ackTimeNanos = totalAckTimeNanos - ack.getDownstreamAckTimeNanos();
                    if (ackTimeNanos < 0) {
                      if (LOG.isDebugEnabled()) {
                        LOG.debug("Calculated invalid ack time: " + ackTimeNanos + "ns.");
                      }
                    } else {
                      datanode.metrics.addPacketAckRoundTripTimeNanos(ackTimeNanos);
                    }
                  }
                  lastPacketInBlock = pkt.lastPacketInBlock;
                }
              }
            } catch (InterruptedException ine) {
              isInterrupted = true;
            } catch (IOException ioe) {
              if (Thread.interrupted()) {
                isInterrupted = true;
              } else {
                // continue to run even if can not read from mirror
                // notify client of the error
                // and wait for the client to shut down the pipeline
                mirrorError = true;
                LOG.info(myString, ioe);
              }
            }

            if (Thread.interrupted() || isInterrupted) {
              /* The receiver thread cancelled this thread. 
               * We could also check any other status updates from the 
               * receiver thread (e.g. if it is ok to write to replyOut). 
               * It is prudent to not send any more status back to the client
               * because this datanode has a problem. The upstream datanode
               * will detect that this datanode is bad, and rightly so.
               */
              LOG.info(myString + ": Thread is interrupted.");
              running = false;
              continue;
            }
            
            // If this is the last packet in block, then close block
            // file and finalize the block before responding success
            if (lastPacketInBlock) {
              BlockReceiver.this.close();
              final long endTime = ClientTraceLog.isInfoEnabled() ? System.nanoTime() : 0;
              block.setNumBytes(replicaInfo.getNumBytes());
              datanode.data.finalizeBlock(block);
              datanode.closeBlock(block, DataNode.EMPTY_DEL_HINT);
              if (ClientTraceLog.isInfoEnabled() && isClient) {
                long offset = 0;
                DatanodeRegistration dnR = 
                  datanode.getDNRegistrationForBP(block.getBlockPoolId());
                ClientTraceLog.info(String.format(DN_CLIENTTRACE_FORMAT,
                      inAddr, myAddr, block.getNumBytes(),
                      "HDFS_WRITE", clientname, offset,
                      dnR.getStorageID(), block, endTime-startTime));
              } else {
                LOG.info("Received block " + block + " of size "
                    + block.getNumBytes() + " from " + inAddr);
              }
            }

            // construct my ack message
            Status[] replies = null;
            if (mirrorError) { // ack read error
              replies = new Status[2];
              replies[0] = Status.SUCCESS;
              replies[1] = Status.ERROR;
            } else {
              short ackLen = type == PacketResponderType.LAST_IN_PIPELINE? 0
                  : ack.getNumOfReplies();
              replies = new Status[1+ackLen];
              replies[0] = Status.SUCCESS;
              for (int i=0; i<ackLen; i++) {
                replies[i+1] = ack.getReply(i);
              }
            }
            PipelineAck replyAck = new PipelineAck(expected, replies, totalAckTimeNanos);
            
            if (replyAck.isSuccess() && 
                 pkt.offsetInBlock > replicaInfo.getBytesAcked())
                replicaInfo.setBytesAcked(pkt.offsetInBlock);

            // send my ack back to upstream datanode
            replyAck.write(upstreamOut);
            upstreamOut.flush();
            if (LOG.isDebugEnabled()) {
              LOG.debug(myString + ", replyAck=" + replyAck);
            }
            if (pkt != null) {
              // remove the packet from the ack queue
              removeAckHead();
              // update bytes acked
            }
        } catch (IOException e) {
          LOG.warn("IOException in BlockReceiver.run(): ", e);
          if (running) {
            try {
              datanode.checkDiskError(e); // may throw an exception here
            } catch (IOException ioe) {
              LOG.warn("DataNode.checkDiskError failed in run() with: ", ioe);
            }
            LOG.info(myString, e);
            running = false;
            if (!Thread.interrupted()) { // failure not caused by interruption
              receiverThread.interrupt();
            }
          }
        } catch (Throwable e) {
          if (running) {
            LOG.info(myString, e);
            running = false;
            receiverThread.interrupt();
          }
        }
      }
      LOG.info(myString + " terminating");
    }
    
    /**
     * Remove a packet from the head of the ack queue
     * 
     * This should be called only when the ack queue is not empty
     */
    private synchronized void removeAckHead() {
      ackQueue.removeFirst();
      notifyAll();
    }
  }
  
  /**
   * This information is cached by the Datanode in the ackQueue.
   */
  private static class Packet {
    final long seqno;
    final boolean lastPacketInBlock;
    final long offsetInBlock;
    final long ackEnqueueNanoTime;

    Packet(long seqno, boolean lastPacketInBlock, long offsetInBlock,
        long ackEnqueueNanoTime) {
      this.seqno = seqno;
      this.lastPacketInBlock = lastPacketInBlock;
      this.offsetInBlock = offsetInBlock;
      this.ackEnqueueNanoTime = ackEnqueueNanoTime;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(seqno=" + seqno
        + ", lastPacketInBlock=" + lastPacketInBlock
        + ", offsetInBlock=" + offsetInBlock
        + ", ackEnqueueNanoTime=" + ackEnqueueNanoTime
        + ")";
    }
  }
}
