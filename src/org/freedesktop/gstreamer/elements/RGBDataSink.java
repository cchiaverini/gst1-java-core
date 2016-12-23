/*
 * Copyright (c) 2007 Wayne Meissner
 *
 * This file is part of gstreamer-java.
 *
 * This code is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * version 3 for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * version 3 along with this work.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.freedesktop.gstreamer.elements;

import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.GhostPad;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.lowlevel.GstBinAPI;
import org.freedesktop.gstreamer.lowlevel.GstNative;

/**
 * Class that allows to pull out buffers from the GStreamer pipeline into
 * the application.
 */
public class RGBDataSink extends Bin {
    private static final GstBinAPI gst = GstNative.load(GstBinAPI.class);
    private final BaseSink videosink;
    private boolean passDirectBuffer = false;
    private Listener listener;

    public static interface Listener {
        void rgbFrame(boolean isPrerollFrame, int width, int height, IntBuffer rgb);
    }

    /**
     * Creates a new instance of RGBDataSink with the given name.
     *
     * @param name The name used to identify this pipeline.
     */
    public RGBDataSink(String name, Listener listener) {
        super(initializer(gst.ptr_gst_bin_new(name)));
        this.listener = listener;
		videosink = (FakeSink) ElementFactory.make("fakesink", name);
        videosink.set("signal-handoffs", true);
        videosink.set("sync", true);
        videosink.connect((BaseSink.HANDOFF) new VideoHandoffListener());
        videosink.connect((BaseSink.PREROLL_HANDOFF) new VideoHandoffListener());

        //
        // Convert the input into 32bit RGB so it can be fed directly to a BufferedImage
        //
        Element conv = ElementFactory.make("videoconvert", "ColorConverter");
        Element videofilter = ElementFactory.make("capsfilter", "ColorFilter");
		StringBuilder caps = new StringBuilder("video/x-raw, format=");
        // JNA creates ByteBuffer using native byte order, set masks according to that.
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
		  caps.append("BGRx");
		} else {
		  caps.append("xRGB");
		}
        videofilter.setCaps(new Caps(caps.toString()));
        addMany(conv, videofilter, videosink);
        Element.linkMany(conv, videofilter, videosink);

        //
        // Link the ghost pads on the bin to the sink pad on the convertor
        //
        addPad(new GhostPad("sink", conv.getStaticPad("sink")));
    }

    public RGBDataSink(String name, Pipeline pipeline, Listener listener) {
        super(initializer(gst.ptr_gst_bin_new(name)));
        this.listener = listener;

        Element element = pipeline.getElementByName(name);
        if (element != null) {

            // TODO: Fix. This doesn't work as it should. getElementByName() returns a
            // BaseSink which cannot be casted to FakeSink.
            videosink = (BaseSink) element;

            videosink.set("signal-handoffs", true);
            videosink.set("sync", true);
            videosink.connect((BaseSink.HANDOFF) new VideoHandoffListener());
            videosink.connect((BaseSink.PREROLL_HANDOFF) new VideoHandoffListener());
        } else {
          videosink = null;
          throw new RuntimeException("Element with name " + name + " not found in the pipeline");
        }
    }

    /**
     * Sets the listener to null. This should be used when disposing
     * the parent object that contains the listener method, to make sure
     * that no dangling references remain to the parent.
     */
    public void removeListener() {
      this.listener = null;
    }

    /**
     * Indicate whether the {@link RGBDataSink} should pass the native {@link java.nio.IntBuffer}
     * to the listener, or should copy it to a heap buffer.  The default is to pass
     * a heap {@link java.nio.IntBuffer} copy of the data
     * @param passThru If true, pass through the native IntBuffer instead of
     * copying it to a heap IntBuffer.
     */
    public void setPassDirectBuffer(boolean passThru) {
        this.passDirectBuffer = passThru;
    }

    /**
     * Gets the actual gstreamer sink element.
     *
     * @return a BaseSink
     */
    public BaseSink getSinkElement() {
        return videosink;
    }

    class VideoHandoffListener implements BaseSink.HANDOFF, BaseSink.PREROLL_HANDOFF {
        public void handoff(BaseSink sink, Buffer buffer, Pad pad) {
        	doHandoff(buffer, pad, false);
        }

        public void prerollHandoff(BaseSink sink, Buffer buffer, Pad pad) {
        	doHandoff(buffer, pad, true);
    	}

        private void doHandoff(Buffer buffer, Pad pad, boolean isPrerollFrame) {

            if (listener != null) {
                Caps caps = pad.getNegotiatedCaps();
                Structure struct = caps.getStructure(0);

                int width = struct.getInteger("width");
                int height = struct.getInteger("height");
                if (width < 1 || height < 1) {
                    return;
                }
                IntBuffer rgb;
                if (passDirectBuffer) {
                    rgb = buffer.map(false).asIntBuffer();
                } else {
                    rgb = IntBuffer.allocate(width * height);
                    rgb.put(buffer.map(false).asIntBuffer()).flip();
                }
                listener.rgbFrame(isPrerollFrame, width, height, rgb);

                buffer.unmap();
//                rgb.clear();
            }
            //
            // Dispose of the gstreamer buffer immediately to avoid more being
            // allocated before the java GC kicks in
            //
            buffer.dispose();
        }
    }
}

