package edu.illinois.sba.camera2raw;

import java.nio.ByteBuffer;

/**
 * Created by meowle on 11/6/2015.
 */
public class Native {
    public native static void saveImageAsCSV(String str, ByteBuffer byteBuffer, int width, int height);
    public native static void saveImageAsBin(String str, ByteBuffer byteBuffer, int width, int height);
}
