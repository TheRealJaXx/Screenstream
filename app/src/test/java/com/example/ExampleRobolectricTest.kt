package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.model.AudioMode
import com.example.model.ScreenResolution
import com.example.model.ScreenShareConfig
import com.example.model.TargetFps
import com.example.model.VideoCodec

class ExampleRobolectricTest {

  @Test
  fun testScreenShareDefaultConfig() {
    val config = ScreenShareConfig()
    assertEquals(VideoCodec.H264, config.codec)
    assertEquals(ScreenResolution.RES_720P, config.resolution)
    assertEquals(TargetFps.FPS_60, config.fps)
    assertEquals(AudioMode.SYSTEM_AND_MIC, config.audioMode)
    assertEquals(4120, config.tcpPort)
    assertEquals(8080, config.webPort)
  }

  @Test
  fun testScreenResolutionDimensions() {
    assertTrue(ScreenResolution.RES_1080P.width == 1920 && ScreenResolution.RES_1080P.height == 1080)
    assertTrue(ScreenResolution.RES_720P.width == 1280 && ScreenResolution.RES_720P.height == 720)
    assertTrue(ScreenResolution.RES_540P.width == 960 && ScreenResolution.RES_540P.height == 540)
  }
}
