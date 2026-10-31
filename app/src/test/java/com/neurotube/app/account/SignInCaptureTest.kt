/*
 * Copyright (C) 2026 NeuroTube | A-EDev
 *
 * This file is part of NeuroTube (https://github.com/NETO-DEV66/NeuroTube).
 *
 * NeuroTube is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package com.neurotube.app.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignInCaptureTest {
    @Test
    fun `a youtube page is recognised on any of its hosts`() {
        assertThat(SignInCapture.isYouTubeUrl("https://www.youtube.com/")).isTrue()
        assertThat(SignInCapture.isYouTubeUrl("https://youtube.com/feed/library")).isTrue()
        assertThat(SignInCapture.isYouTubeUrl("https://m.youtube.com/watch?v=abc")).isTrue()
        assertThat(SignInCapture.isYouTubeUrl("https://music.youtube.com/library")).isTrue()
        assertThat(SignInCapture.isYouTubeUrl("https://www.youtube.com/feed/subscriptions")).isTrue()
    }

    @Test
    fun `a host that merely starts with a youtube origin is not youtube`() {
        assertThat(SignInCapture.isYouTubeUrl("https://www.youtube.com.example.com/steal")).isFalse()
        assertThat(SignInCapture.isYouTubeUrl("https://youtube.com.evil.test/")).isFalse()
        assertThat(SignInCapture.isYouTubeUrl("https://notyoutube.com/")).isFalse()
    }

    @Test
    fun `a google sign-in hop is not a youtube page`() {
        assertThat(SignInCapture.isYouTubeUrl("https://accounts.google.com/ServiceLogin")).isFalse()
        assertThat(SignInCapture.isYouTubeUrl("https://consent.youtube.com/m?continue=x")).isFalse()
    }

    /**
     * `accounts.youtube.com` serves the `SetSID` hop, which is how the session reaches the YouTube
     * hosts. A session read from it is not a finished one, so it must not end a capture.
     */
    @Test
    fun `the mid-flow setSID host is not a capture page`() {
        assertThat(SignInCapture.isYouTubeUrl("https://accounts.youtube.com/accounts/SetSID")).isFalse()
    }

    @Test
    fun `a missing or unusable url is not a youtube page`() {
        assertThat(SignInCapture.isYouTubeUrl(null)).isFalse()
        assertThat(SignInCapture.isYouTubeUrl("")).isFalse()
        assertThat(SignInCapture.isYouTubeUrl("   ")).isFalse()
        assertThat(SignInCapture.isYouTubeUrl("about:blank")).isFalse()
    }

    @Test
    fun `the host is read past a userinfo section and a port`() {
        assertThat(SignInCapture.hostOf("https://user:pw@www.youtube.com:443/x")).isEqualTo("www.youtube.com")
        assertThat(SignInCapture.hostOf("https://WWW.YouTube.COM/x")).isEqualTo("www.youtube.com")
        assertThat(SignInCapture.hostOf("https://www.youtube.com?q=1")).isEqualTo("www.youtube.com")
        assertThat(SignInCapture.hostOf("https://www.youtube.com#frag")).isEqualTo("www.youtube.com")
        assertThat(SignInCapture.hostOf(null)).isNull()
    }

    @Test
    fun `the session origins cover the hosts the jar is read from`() {
        assertThat(SignInCapture.YOUTUBE_ORIGINS)
            .containsExactly("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
        assertThat(SignInCapture.GOOGLE_ORIGINS).containsAtLeast("youtube.com", "accounts.google.com")
    }

    /** A capture host must also be a host the jar is read from, or the poll would never see a session. */
    @Test
    fun `every capture host is read when the jar is collected`() {
        assertThat(SignInCapture.YOUTUBE_ORIGINS).containsAtLeastElementsIn(SignInCapture.CAPTURE_HOSTS)
    }
}
