package com.neurotube.app.innertube.models.response

import com.neurotube.app.innertube.models.AccountInfo
import com.neurotube.app.innertube.models.Runs
import com.neurotube.app.innertube.models.Thumbnails
import com.neurotube.app.innertube.models.Thumbnail
import kotlinx.serialization.Serializable

@Serializable
data class AccountMenuResponse(
    val actions: List<Action> = emptyList(),
) {
    @Serializable
    data class Action(
        val openPopupAction: OpenPopupAction,
    ) {
        @Serializable
        data class OpenPopupAction(
            val popup: Popup,
        ) {
            @Serializable
            data class Popup(
                val multiPageMenuRenderer: MultiPageMenuRenderer,
            ) {
                @Serializable
                data class MultiPageMenuRenderer(
                    val header: Header? = null,
                ) {
                    @Serializable
                    data class Header(
                        val activeAccountHeaderRenderer: ActiveAccountHeaderRenderer,
                    ) {
                        @Serializable
                        data class ActiveAccountHeaderRenderer(
                            val accountName: Runs,
                            val email: Runs? = null,
                            val channelHandle: Runs? = null,
                            val accountPhoto: Thumbnails,
                        ) {
                            /**
                             * The account's own name, or `null` when the menu carried no header for
                             * it. The menu answers with an empty header for a session InnerTube has
                             * not accepted yet, which is a sign-in failure — not a reason to throw.
                             */
                            fun toAccountInfo(): AccountInfo? {
                                val name = accountName.runs?.firstOrNull()?.text?.takeIf(String::isNotBlank)
                                return name?.let {
                                    AccountInfo(
                                        name = it,
                                        email = email?.runs?.firstOrNull()?.text,
                                        channelHandle = channelHandle?.runs?.firstOrNull()?.text,
                                        thumbnailUrl = accountPhoto.thumbnails.lastOrNull()?.url,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
