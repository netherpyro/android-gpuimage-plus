package org.wysaid.view;/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.os.Environment;
import android.util.Log;

import org.wysaid.common.Common;
import org.wysaid.common.SharedContext;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLContext;

import static java.lang.Thread.sleep;
import static org.wysaid.common.Common.NO_TEXTURE;

/**
 * Extract frames from an MP4 using MediaExtractor, MediaCodec, and GLES.  Put a .mp4 file
 * in "/sdcard/source.mp4" and look for output files named "/sdcard/frame-XX.png".
 * <p>
 * This uses various features first available in Android "Jellybean" 4.1 (API 16).
 * <p>
 * (This was derived from bits and pieces of CTS tests, and is packaged as such, but is not
 * currently part of CTS.)
 */
class ExtractMpegFrames {
    private static final String TAG = "ExtractMpegFrames";
    private static final boolean VERBOSE = false;           // lots of logging

    private MediaCodec decoder;
    private MediaExtractor extractor;
    private MediaFormat format;
    private EGLContext rootContext;
    private SharedContext localSharedContext;
    private int trackIndex;
    private int mWidth;
    private int mHeight;
    private int texId = NO_TEXTURE;

    private IntBuffer mGLRgbBuffer;

    void startExtractMpegFrames() throws Throwable {
        ExtractMpegFramesWrapper.runTest(this);
    }

    int prepare(EGLContext rootContext, String fileName) {
        this.rootContext = rootContext;

        try {
            File inputFile = new File(Environment.getExternalStorageDirectory(), fileName);   // must be an absolute path
            // The MediaExtractor error messages aren't very useful.  Check to see if the input
            // file exists so we can throw a better one if it's not there.
            if (!inputFile.canRead()) {
                throw new FileNotFoundException("Unable to read " + inputFile);
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.toString());
            trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + inputFile);
            }

            extractor.selectTrack(trackIndex);

            format = extractor.getTrackFormat(trackIndex);
            mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            texId = Common.genBlankTextureID(mWidth, mHeight);

        } catch (IOException e) {
            Log.e(TAG, "failed prepare", e);
        }

        return texId;
    }

    /**
     * Wraps extractMpegFrames().  This is necessary because SurfaceTexture will try to use
     * the looper in the current thread if one exists, and the CTS tests create one on the
     * test thread.
     * <p>
     * The wrapper propagates exceptions thrown by the worker thread back to the caller.
     */
    private static class ExtractMpegFramesWrapper implements Runnable {
        private Throwable mThrowable;
        private ExtractMpegFrames mTest;

        private ExtractMpegFramesWrapper(ExtractMpegFrames test) {
            mTest = test;
        }

        @Override
        public void run() {
            try {
                mTest.extractMpegFrames();
            } catch (Throwable th) {
                mThrowable = th;
            }
        }

        /**
         * Entry point.
         */
        static void runTest(ExtractMpegFrames obj) throws Throwable {
            ExtractMpegFramesWrapper wrapper = new ExtractMpegFramesWrapper(obj);
            Thread th = new Thread(wrapper, "codec test");
            th.start();
            if (wrapper.mThrowable != null) {
                throw wrapper.mThrowable;
            }
        }
    }

    /**
     * Tests extraction from an MP4 to a series of PNG files.
     * <p>
     * We scale the video to 640x480 for the PNG just to demonstrate that we can scale the
     * video with the GPU.  If the input video has a different aspect ratio, we could preserve
     * it by adjusting the GL viewport to get letterboxing or pillarboxing, but generally if
     * you're extracting frames you don't want black bars.
     */
    private void extractMpegFrames() throws IOException {
        localSharedContext = SharedContext.create(rootContext, mWidth, mHeight);

        // Create a MediaCodec decoder, and configure it with the MediaFormat from the
        // extractor.  It's very important to use the format from the extractor because
        // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
        String mime = format.getString(MediaFormat.KEY_MIME);
        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();

        doExtract(extractor, trackIndex, decoder);

        // release everything we grabbed
        if (decoder != null) {
            decoder.stop();
            decoder.release();
        }

        if (extractor != null) {
            extractor.release();
        }

        if (texId != Common.NO_TEXTURE) {
            GLES20.glDeleteTextures(1, new int[]{texId}, 0);
            texId = Common.NO_TEXTURE;
        }

        localSharedContext.release();
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }

    /**
     * Work loop.
     */
    private void doExtract(MediaExtractor extractor, int trackIndex, MediaCodec decoder) {

        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int inputChunk = 0;
        boolean outputDone = false;
        boolean inputDone = false;
        long startMs = System.currentTimeMillis();

        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "loop");

            // Feed more data to the decoder.
            if (!inputDone) {

                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    int chunkSize = extractor.readSampleData(inputBuf, 0);
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;

                        if (VERBOSE) Log.d(TAG, "sent input EOS");

                    } else {

                        if (extractor.getSampleTrackIndex() != trackIndex) {
                            Log.w(TAG, "WEIRD: got sample from track " + extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }

                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, presentationTimeUs, 0);

                        if (VERBOSE) Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" + chunkSize);

                        inputChunk++;
                        extractor.advance();
                    }

                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
            if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (VERBOSE) Log.d(TAG, "no output from decoder available");

            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                if (VERBOSE) Log.d(TAG, "decoder output buffers changed");
                outputBuffers = decoder.getOutputBuffers();

            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = decoder.getOutputFormat();
                if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat);

            } else if (decoderStatus < 0) {
                throw new IllegalStateException("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
            } else { // decoderStatus >= 0

                if (VERBOSE) Log.d(TAG, "decoder given buffer " + decoderStatus + " (size=" + info.size + ")");

                // All decoded frames have been rendered, we can stop playing now
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (VERBOSE) Log.d(TAG, "output EOS");
                    outputDone = true;
                }

                ByteBuffer buffer = outputBuffers[decoderStatus];
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);

                Log.d(TAG, "offset: " + info.offset + " size: " + info.size);

                final byte[] ba = new byte[buffer.remaining()];
                buffer.get(ba);

                if (mGLRgbBuffer == null) {
                    mGLRgbBuffer = IntBuffer.allocate(mHeight * mWidth);
                }

                YUVtoRBGA(ba, mWidth, mHeight, mGLRgbBuffer.array());
                Common.loadNormalTextureID(mGLRgbBuffer, mWidth, mHeight, texId);

                // We use a very simple clock to keep the video FPS, or the video
                // playback will be too fast
                while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
                    try {
                        sleep(600);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }

                decoder.releaseOutputBuffer(decoderStatus, false);
            }
        }
    }

    public static native void YUVtoRBGA(byte[] yuv, int width, int height, int[] out);
}