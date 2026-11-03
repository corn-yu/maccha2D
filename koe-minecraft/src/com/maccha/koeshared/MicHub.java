package com.maccha.koeshared;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * マイクの音を、こえマイクラ・こえまほうの両方に配る係（マイクは1つしか開けないため）
 * どちらの MOD にも まったく同じこのファイルが入っていて、先に読まれたほうが使われる。
 * 中身を変えるときは、両方の MOD で同じにすること（version を上げる）
 */
public final class MicHub {
    // このファイルの版（両方の MOD で同じにする）
    public static final int VERSION = 1;
    // 16kHz・16bit・モノラル
    public static final float SAMPLE_RATE = 16000f;
    // 1回に配る音の大きさ（0.05秒ぶん）
    public static final int CHUNK_BYTES = 1600;

    static final List<BlockingQueue<byte[]>> listeners = new CopyOnWriteArrayList<>();
    static Thread thread;
    // マイクが開けなかったときの理由（開けていれば null）
    public static volatile String error;

    private MicHub() {
    }

    /** 音を受けとる入れ物をもらう（はじめて呼ばれたときにマイクを開く） */
    public static synchronized BlockingQueue<byte[]> listen() {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(400);
        listeners.add(queue);
        // もうマイクがだめになっていたら、すぐ「おしまい」を送る
        if (error != null) queue.offer(new byte[0]);
        if (thread == null) {
            thread = new Thread(MicHub::run, "koe-mic");
            thread.setDaemon(true);
            thread.start();
        }
        return queue;
    }

    /** 受けとるのをやめる */
    public static void stop(BlockingQueue<byte[]> queue) {
        listeners.remove(queue);
    }

    /** マイクを開いて、読んだ音を全員に配りつづける */
    static void run() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            TargetDataLine microphone = AudioSystem.getTargetDataLine(format);
            microphone.open(format, 4096);
            microphone.start();
            byte[] buffer = new byte[CHUNK_BYTES];
            while (true) {
                int count = microphone.read(buffer, 0, buffer.length);
                if (count <= 0) continue;
                byte[] copy = java.util.Arrays.copyOf(buffer, count);
                // 受けとりがおそくて いっぱいなら、古い音をすてて新しい音を入れる
                for (BlockingQueue<byte[]> queue : listeners) {
                    while (!queue.offer(copy)) queue.poll();
                }
            }
        } catch (Throwable problem) {
            error = String.valueOf(problem.getMessage());
            // 待っている人に「おしまい」の空の音を送る
            for (BlockingQueue<byte[]> queue : listeners) queue.offer(new byte[0]);
        }
    }
}
