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

/**
 * The parts of sign-in capture that are pure logic: which origins matter, whether a navigation has
 * landed on YouTube, and when a jar holds a usable session.
 *
 * Kept free of Android types so the policy can be exercised directly — the activity only owns the
 * WebView and the timers.
 */
internal object SignInCapture {
    /**
     * Every origin the session cookie may be set on. Google sets it on whichever host completes the
     * flow, so the jar is read from all of them rather than assuming one.
     */
    val YOUTUBE_ORIGINS =
        listOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
        )

    /**
     * The pages a captured session may be read from: the YouTube surfaces the sign-in flow returns
     * to.
     *
     * Deliberately exact, and deliberately not "any `youtube.com` host". Google's own
     * `accounts.youtube.com` (the `SetSID` hop) and `consent.youtube.com` (the consent
     * interstitial) are on the way through the flow, and a session read from either is not yet a
     * finished one — capturing there risks storing a credential YouTube then refuses. Exact hosts
     * also keep a look-alike such as `www.youtube.com.example.com` from triggering a capture, which
     * a prefix test would allow.
     */
    val CAPTURE_HOSTS =
        listOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
        )

    /**
     * Hosts whose cookies are Google's sign-in state rather than the app's. Cleared when the WebView
     * is finished with the jar so a half-finished attempt cannot be resumed from it.
     */
    val GOOGLE_ORIGINS =
        listOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "accounts.google.com",
            "accounts.youtube.com",
            "consent.youtube.com",
            "myaccount.google.com",
            "google.com",
            "www.google.com",
        )

    /** True when [url] is a YouTube page a session may be captured from. */
    fun isYouTubeUrl(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return host in CAPTURE_HOSTS
    }

    /** The lower-case host of [url], or `null` when it has none. */
    fun hostOf(url: String?): String? {
        val value = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val schemeEnd = value.indexOf("://")
        val authority = value.substring(if (schemeEnd >= 0) schemeEnd + 3 else 0)
        val host =
            authority
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
        return host.lowercase().takeIf(String::isNotEmpty)
    }
}
