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

package com.neurotube.app.innertube.models.response

import com.google.common.truth.Truth.assertThat
import com.neurotube.app.innertube.models.Run
import com.neurotube.app.innertube.models.Runs
import com.neurotube.app.innertube.models.Thumbnail
import com.neurotube.app.innertube.models.Thumbnails
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The account menu is how a captured session is proved to be a real one. A menu that carries no
 * header for the viewer has to read as "not signed in" rather than throwing, or a session that is
 * merely slow to propagate is reported as a failed sign-in.
 */
class AccountMenuResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a menu with an account header reads the account`() {
        val response =
            json.decodeFromString<AccountMenuResponse>(
                """
                {
                  "actions": [
                    {
                      "openPopupAction": {
                        "popup": {
                          "multiPageMenuRenderer": {
                            "header": {
                              "activeAccountHeaderRenderer": {
                                "accountName": { "runs": [ { "text": "Test Channel" } ] },
                                "email": { "runs": [ { "text": "test@example.com" } ] },
                                "channelHandle": { "runs": [ { "text": "@testchannel" } ] },
                                "accountPhoto": {
                                  "thumbnails": [ { "url": "https://example.com/s.jpg", "width": 88 } ]
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val header =
            response.actions
                .firstOrNull()
                ?.openPopupAction
                ?.popup
                ?.multiPageMenuRenderer
                ?.header
                ?.activeAccountHeaderRenderer

        assertThat(header).isNotNull()
        assertThat(header?.toAccountInfo())
            .isEqualTo(
                com.neurotube.app.innertube.models.AccountInfo(
                    name = "Test Channel",
                    email = "test@example.com",
                    channelHandle = "@testchannel",
                    thumbnailUrl = "https://example.com/s.jpg",
                ),
            )
    }

    @Test
    fun `a menu with no header is not an account`() {
        val response = json.decodeFromString<AccountMenuResponse>("""{"actions":[]}""")

        val header =
            response.actions
                .firstOrNull()
                ?.openPopupAction
                ?.popup
                ?.multiPageMenuRenderer
                ?.header
                ?.activeAccountHeaderRenderer

        assertThat(header).isNull()
    }

    @Test
    fun `a header without a name is not an account`() {
        val header =
            AccountMenuResponse.Action.OpenPopupAction.Popup.MultiPageMenuRenderer.Header.ActiveAccountHeaderRenderer(
                accountName = Runs(runs = emptyList()),
                email = Runs(runs = listOf(Run("test@example.com"))),
                channelHandle = null,
                accountPhoto = Thumbnails(thumbnails = listOf(Thumbnail("https://example.com/s.jpg"))),
            )

        assertThat(header.toAccountInfo()).isNull()
    }

    @Test
    fun `a blank name is not an account`() {
        val header =
            AccountMenuResponse.Action.OpenPopupAction.Popup.MultiPageMenuRenderer.Header.ActiveAccountHeaderRenderer(
                accountName = Runs(runs = listOf(Run("   "))),
                email = null,
                channelHandle = null,
                accountPhoto = Thumbnails(),
            )

        assertThat(header.toAccountInfo()).isNull()
    }

    @Test
    fun `a name without the optional fields still reads the account`() {
        val header =
            AccountMenuResponse.Action.OpenPopupAction.Popup.MultiPageMenuRenderer.Header.ActiveAccountHeaderRenderer(
                accountName = Runs(runs = listOf(Run("Test Channel"))),
                email = null,
                channelHandle = null,
                accountPhoto = Thumbnails(),
            )

        assertThat(header.toAccountInfo()?.name).isEqualTo("Test Channel")
        assertThat(header.toAccountInfo()?.email).isNull()
        assertThat(header.toAccountInfo()?.thumbnailUrl).isNull()
    }
}
