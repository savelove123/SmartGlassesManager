// cayden - modified slightly to accecpt queue instead - tried using default with pipeoutput but we get "Write end dead error"
// https://stackoverflow.com/questions/5453525/write-end-dead-exception-using-pipedinputstream-java
//// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.smartglassesmanager.androidsmartphone.speechrecognition.vosk;

import org.vosk.android.RecognitionListener;

import android.os.Handler;
import android.os.Looper;

import org.vosk.Recognizer;

import java.lang.InterruptedException;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.BlockingQueue;

/**
 * Service that recognizes stream audio in a  thread, passes it to a recognizer and emits
 * recognition results. Recognition events are passed to a client using
 * {@link RecognitionListener}
 */
public class SpeechStreamQueueServiceVosk {
    private final String TAG = "WearableAi_SpeechStreamQueueServiceVosk";

    private boolean shouldDie = false;

    private final Recognizer recognizer;
    private final BlockingQueue<byte []> inputStream;
    private final int sampleRate;
    private final static float BUFFER_SIZE_SECONDS = 0.1f;
    private final int bufferSize;

    private Thread recognizerThread;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Creates speech service.
     **/
    public SpeechStreamQueueServiceVosk(Recognizer recognizer, BlockingQueue inputStream, float sampleRate) {
        this.recognizer = recognizer;
        this.sampleRate = (int) sampleRate;
        this.inputStream = inputStream;
//        this.bufferSize = bufferSize; //Math.round(this.sampleRate * BUFFER_SIZE_SECONDS * 2);
        this.bufferSize = Math.round(this.sampleRate * BUFFER_SIZE_SECONDS * 2);
    }

    /**
     * Starts recognition. Does nothing if recognition is active.
     *
     * @return true if recognition was actually started
     */
    public boolean start(RecognitionListener listener) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener);
        recognizerThread.start();
        return true;
    }

    /**
     * Starts recognition. After specified timeout listening stops and the
     * endOfSpeech signals about that. Does nothing if recognition is active.
     * <p>
     * timeout - timeout in milliseconds to listen.
     *
     * @return true if recognition was actually started
     */
    public boolean start(RecognitionListener listener, int timeout) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener, timeout);
        recognizerThread.start();
        return true;
    }

    /**
     * Stops recognition. All listeners should receive final result if there is
     * any. Does nothing if recognition is not active.
     *
     * @return true if recognition was actually stopped
     */
    public boolean stop() {
        if (null == recognizerThread)
            return false;

        try {
            shouldDie = true;
            recognizerThread.interrupt();
            recognizerThread.join();
        } catch (InterruptedException e) {
            // Restore the interrupted status.
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }

        recognizerThread = null;
        return true;
    }

    private final class RecognizerThread extends Thread {

        private int remainingSamples;
        private final int timeoutSamples;
        private final static int NO_TIMEOUT = -1;
        RecognitionListener listener;

        public RecognizerThread(RecognitionListener listener, int timeout) {
            this.listener = listener;
            if (timeout != NO_TIMEOUT)
                this.timeoutSamples = timeout * sampleRate / 1000;
            else
                this.timeoutSamples = NO_TIMEOUT;
            this.remainingSamples = this.timeoutSamples;
        }

        public RecognizerThread(RecognitionListener listener) {
            this(listener, NO_TIMEOUT);
        }

        @Override
        public void run() {

            byte[] buffer;// = new byte[bufferSize];

            while (!shouldDie && !interrupted()
                    && ((timeoutSamples == NO_TIMEOUT) || (remainingSamples > 0))) {
                try {
                    //int nread = inputStream.read(buffer, 0, buffer.length);
                    buffer = inputStream.poll(bufferSize, TimeUnit.MILLISECONDS); //we poll so that, if we need to kill this thread with an interrupt, we don't block forever on take() and never leave the loop
                    if (buffer == null){ //if null, we want to loop again
                        continue;
                    }
                    int nread = buffer.length;
                    if (nread < 0) {
                        break;
                    } else {
                        boolean isSilence = recognizer.acceptWaveForm(buffer, nread);
                        if (isSilence) {
                            final String result = recognizer.getResult();
                            mainHandler.post(() -> listener.onResult(result));
                        } else {
                            final String partialResult = recognizer.getPartialResult();
                            mainHandler.post(() -> listener.onPartialResult(partialResult));
                        }
                    }

                    if (timeoutSamples != NO_TIMEOUT) {
                        remainingSamples = remainingSamples - nread;
                    }

                } catch (InterruptedException e) {
                    mainHandler.post(() -> listener.onError(e));
                }
            }

            // If we met timeout signal that speech ended
            if (timeoutSamples != NO_TIMEOUT && remainingSamples <= 0) {
                mainHandler.post(() -> listener.onTimeout());
            } else {
                final String finalResult = recognizer.getFinalResult();
                mainHandler.post(() -> listener.onFinalResult(finalResult));
            }
        }
    }
}
