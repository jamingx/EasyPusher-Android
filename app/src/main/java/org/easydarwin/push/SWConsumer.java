package org.easydarwin.push;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.sw.X264Encoder;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Created by apple on 2017/5/13.
 */

public class SWConsumer extends Thread implements VideoConsumer {
    private static final String TAG = "SWConsumer";
    private int mHeight;
    private int mWidth;
    private X264Encoder x264;
    private final Pusher mPusher;
    private volatile boolean mVideoStarted;
    private byte []yv12;
    private EasyMuxer muxer;
    private MediaFormat newFormat;

    public SWConsumer(Context context, Pusher pusher){
        mPusher = pusher;
    }
    @Override
    public void onVideoStart(int width, int height) {
        this.mWidth = width;
        this.mHeight = height;

        x264 = new X264Encoder();
        int bitrate = (int) 300000;
//        260->37
//        460->37

//        int bitrate = 2000000;
//        bitrate = (int) (mWidth * mHeight * 30 * 2 * 0.1);
        if (width >= 1920 || height >= 1920){
            bitrate = 4000;
        }else if (width >= 1280 || height >= 1280){
            bitrate = 2000;
        }else if (width >= 960 || height >= 960){
            bitrate = 1000;
        }else if (width >= 640 || height >= 640){
            bitrate = 500;
        }else if (width >= 480 || height >= 480){
            bitrate = 300;
        }
        x264.create(width, height, 20, bitrate);
        mVideoStarted = true;
        start();
    }


    class TimedBuffer {
        byte[] buffer;
        long time;

        public TimedBuffer(byte[] data) {
            buffer = data;
            time = System.nanoTime() / 1000000;
        }
    }

    private ArrayBlockingQueue<TimedBuffer> yuvs = new ArrayBlockingQueue<TimedBuffer>(2);
    private ArrayBlockingQueue<byte[]> yuv_caches = new ArrayBlockingQueue<byte[]>(10);

    @Override
    public void run(){

        byte[]h264 = new byte[mWidth*mHeight*3/2];
        byte[] keyFrm = new byte[1];
        int []outLen = new int[1];
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long lastLog = SystemClock.elapsedRealtime();
        do {
            try {
                int r = 0;
                TimedBuffer tb = yuvs.take();
                byte[] data = tb.buffer;
                long begin = System.currentTimeMillis();
                keyFrm[0] = 0;
                Pusher p = mPusher;
                if ((p != null && p.pushing()) || muxer != null) {
                    r = x264.encode(data, 0, h264, 0, outLen, keyFrm);
                    if (r > 0) {
                        Log.i(TAG, String.format("encode spend:%d ms. keyFrm:%d", System.currentTimeMillis() - begin, keyFrm[0]));
//                                newBuf = new byte[outLen[0]];
//                                System.arraycopy(h264, 0, newBuf, 0, newBuf.length);

                        int bitrate = BitrateStat.stat("Bitrate" + getName(), outLen[0]);
                        if (SystemClock.elapsedRealtime() - lastLog >= 1000) {
                            lastLog = SystemClock.elapsedRealtime();
                            Log.i(TAG, String.format("bitrate:%.02fkbps", bitrate * 8.0f / 1000));
                        }
                    }
                    if (keyFrm[0] == 1) {
                        synchronized (SWConsumer.this) {
                            if (newFormat == null) {
                                // init format

                                MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth, mHeight);
                                byte[] dataOut = new byte[128];
                                int[] dataOutLen = new int[]{128};
                                int result = getXPS(h264, 0, 256, dataOut, dataOutLen, 7);
                                if (result >= 0) {
                                    ByteBuffer csd0 = ByteBuffer.allocate(dataOutLen[0]);
                                    csd0.put(dataOut, 0, dataOutLen[0]);
                                    csd0.clear();
                                    Log.i(TAG, String.format("CSD-0 searched"));
                                    fmt.setByteBuffer("csd-0", csd0);
                                }
                                dataOutLen[0] = 128;
                                result = getXPS(h264, 0, 256, dataOut, dataOutLen, 8);
                                if (result >= 0) {
                                    ByteBuffer csd1 = ByteBuffer.allocate(dataOutLen[0]);
                                    csd1.put(dataOut, 0, dataOutLen[0]);
                                    csd1.clear();
                                    Log.i(TAG, String.format("CSD-1 searched"));
                                    fmt.setByteBuffer("csd-1", csd1);
                                }
                                newFormat = fmt;
                                if (muxer != null) muxer.addTrack(newFormat, true);
                            }
                        }
                    }
                    yuv_caches.offer(data);
                    synchronized (SWConsumer.this) {
                        if (muxer != null) {
                            bi.flags = 0;
                            bi.presentationTimeUs = tb.time * 1000;
                            bi.flags |= keyFrm[0] == 1 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            bi.offset = 0;
                            bi.size = outLen[0];
                            ByteBuffer bf = ByteBuffer.wrap(h264, 0, outLen[0]);
                            muxer.pumpStream(bf, bi);
                        }
                    }
                    if (p != null) {
                        p.push(h264, 0, outLen[0], tb.time, 1);
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }while (mVideoStarted);
    }


    final int millisPerframe = 1000/20;
    long lastPush = 0;
    @Override
    public int onVideo(byte[] data, int format) {
        try {
            if (lastPush == 0) {
                lastPush = System.currentTimeMillis();
            }
            long time = System.currentTimeMillis() - lastPush;
            if (time >= 0) {
                time = millisPerframe - time;
                if (time > 0) Thread.sleep(time / 2);
            }
            byte[] buffer = yuv_caches.poll();
            if (buffer == null || buffer.length != data.length) {
                buffer = new byte[data.length];
            }
            JNIUtil.ConvertFromI420(data, buffer, mWidth, mHeight, 1);
            yuvs.offer(new TimedBuffer(buffer));
            if (time > 0) Thread.sleep(time / 2);
            lastPush = System.currentTimeMillis();
        }catch (InterruptedException ex){
            ex.printStackTrace();
        }
        return 0;
    }

    @Override
    public void onVideoStop() {
        do {
            mVideoStarted = false;
            try {
                interrupt();
                join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }while (isAlive());
        if (x264 != null) {
            x264.close();
        }
        x264 = null;
    }

    @Override
    public synchronized void setMuxer(EasyMuxer muxer) {
        if (muxer != null) {
            if (newFormat != null)
                muxer.addTrack(newFormat, true);
        }
        this.muxer = muxer;
    }


    private static int getXPS(byte[] data, int offset, int length, byte[] dataOut, int[] outLen, int type) {
        int i;
        int pos0;
        int pos1;
        pos0 = -1;
        length = Math.min(length, data.length);
        for (i = offset; i < length - 4; i++) {
            if ((0 == data[i]) && (0 == data[i + 1]) && (1 == data[i + 2]) && (type == (0x0F & data[i + 3]))) {
                pos0 = i;
                break;
            }
        }
        if (-1 == pos0) {
            return -1;
        }
        if (pos0 > 0 && data[pos0 - 1] == 0) { // 0 0 0 1
            pos0 = pos0 - 1;
        }
        pos1 = -1;
        for (i = pos0 + 4; i < length - 4; i++) {
            if ((0 == data[i]) && (0 == data[i + 1]) && (1 == data[i + 2])) {
                pos1 = i;
                break;
            }
        }
        if (-1 == pos1 || pos1 == 0) {
            return -2;
        }
        if (data[pos1 - 1] == 0) {
            pos1 -= 1;
        }
        if (pos1 - pos0 > outLen[0]) {
            return -3; // 输入缓冲区太小
        }
        dataOut[0] = 0;
        System.arraycopy(data, pos0, dataOut, 0, pos1 - pos0);
        // memcpy(pXPS+1, pES+pos0, pos1-pos0);
        // *pMaxXPSLen = pos1-pos0+1;
        outLen[0] = pos1 - pos0;
        return pos1;
    }

}
