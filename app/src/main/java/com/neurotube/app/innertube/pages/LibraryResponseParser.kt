package com.neurotube.app.innertube.pages

import com.neurotube.app.innertube.models.GridRenderer
import com.neurotube.app.innertube.models.MusicPlaylistShelfRenderer
import com.neurotube.app.innertube.models.MusicShelfRenderer
import com.neurotube.app.innertube.models.SectionListRenderer
import com.neurotube.app.innertube.models.YTItem
import com.neurotube.app.innertube.models.getContinuation
import com.neurotube.app.innertube.models.response.BrowseResponse

/**
 * Reads the shape of the music library, which is not uniform. A first page puts its items in a grid,
 * a shelf, or a playlist shelf directly under the section list; some accounts wrap that in an item
 * section; and a later page arrives as a continuation holding a bare grid, a shelf, a playlist
 * shelf, or a whole section list. Some libraries hand the next slice back as an append action rather
 * than as continuation contents. These helpers flatten all of that into items plus a next token.
 *
 * Every one of those shapes has to be read. A page whose shape is walked past yields no items and,
 * worse, no next token, so the walk stops and only the first page is imported — which is how an
 * account with more liked songs than one page holds ends up with a shorter list in the app.
 */
object LibraryResponseParser {
    /** The items and next token of the library tab at [tabIndex]. */
    fun pageOf(
        response: BrowseResponse,
        tabIndex: Int = 0,
    ): LibraryPage {
        val section = firstSection(response, tabIndex)
        val fallback = sectionToken(response, tabIndex)
        val grid = section?.gridRenderer
        if (grid != null) {
            return LibraryPage(
                items = grid.items.toLibraryItems(),
                continuation =
                    grid.continuations?.getContinuation()
                        ?: grid.items.trailingContinuation()
                        ?: fallback,
            )
        }
        val shelf = section?.musicShelfRenderer
        return LibraryPage(
            items = shelf.toItems(),
            continuation =
                shelf?.continuations?.getContinuation()
                    ?: shelf?.contents?.getContinuation()
                    ?: fallback,
        )
    }

    /**
     * The token on the section list itself, when the page carries exactly one section.
     *
     * A single-section library page — every `FEmusic_liked_*` page is one — hangs the next-page
     * token off the section list rather than the grid or shelf inside it. That token is then
     * unambiguously this section's, so it is taken as a fallback; without it the library renders
     * only its first page. With more than one section the token belongs to the list as a whole and
     * must not be attributed to the first one.
     */
    private fun sectionToken(
        response: BrowseResponse,
        tabIndex: Int,
    ): String? {
        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs ?: return null
        if (tabIndex !in tabs.indices) return null
        val sectionList = tabs[tabIndex].tabRenderer.content?.sectionListRenderer ?: return null
        if (sectionList.contents?.size != 1) return null
        return sectionList.continuations?.getContinuation()
    }

    /** The items and next token of a library continuation response. */
    fun continuationPageOf(response: BrowseResponse): LibraryContinuationPage {
        val contents = response.continuationContents

        contents?.gridContinuation?.let { grid ->
            val page =
                LibraryContinuationPage(
                    items = grid.items.toLibraryItems(),
                    continuation = grid.continuations?.getContinuation() ?: grid.items.trailingContinuation(),
                )
            if (page.hasContent) return page
        }

        contents?.sectionListContinuation?.let { sectionList ->
            // A continuation of a grid is bare, but a continuation of a shelf repeats the section
            // list and puts the next token on the section list itself.
            val page =
                fromSlice(
                    section = sectionList.contents.firstOrNull(),
                    fallbackContinuation = sectionList.continuations?.getContinuation(),
                )
            if (page.hasContent) return page
        }

        contents?.musicPlaylistShelfContinuation?.let { shelf ->
            val page =
                LibraryContinuationPage(
                    items = shelf.contents.toItems(),
                    continuation = shelf.continuations?.getContinuation() ?: shelf.contents?.getContinuation(),
                )
            if (page.hasContent) return page
        }

        contents?.musicShelfContinuation?.let { shelf ->
            val page =
                LibraryContinuationPage(
                    items = shelf.toItems(),
                    continuation = shelf.continuations?.getContinuation() ?: shelf.contents?.getContinuation(),
                )
            if (page.hasContent) return page
        }

        // A library page can hand its next slice back as an appended list instead of as continuation
        // contents; the token then rides on the last appended row.
        val appended =
            response.onResponseReceivedActions
                ?.firstOrNull()
                ?.appendContinuationItemsAction
                ?.continuationItems
                .orEmpty()
        return LibraryContinuationPage(
            items = appended.toItems(),
            continuation = appended.getContinuation(),
        )
    }

    /** Whether a page carried anything, so an empty container cannot shadow a fuller one. */
    private val LibraryContinuationPage.hasContent: Boolean
        get() = items.isNotEmpty() || continuation != null

    /**
     * The section holding a library tab's items. The section list usually starts with the grid or
     * shelf itself, but can start with a playlist shelf, or with an item section wrapping any of
     * them, and that item section can in turn hold a grid, a shelf, a playlist shelf, or a list of
     * section items.
     */
    private fun firstSection(
        response: BrowseResponse,
        tabIndex: Int,
    ): SectionListRenderer.Content? {
        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs ?: return null
        if (tabIndex !in tabs.indices) return null
        val sections =
            tabs[tabIndex]
                .tabRenderer.content
                ?.sectionListRenderer
                ?.contents ?: return null
        val section = sections.firstOrNull() ?: return null
        section.musicPlaylistShelfRenderer?.let { return it.asSection() }
        val itemSection = section.itemSectionRenderer ?: return section

        itemSection.gridRenderer?.let { return SectionListRenderer.Content(gridRenderer = it) }
        itemSection.musicShelfRenderer?.let { return SectionListRenderer.Content(musicShelfRenderer = it) }
        val nestedGrid = itemSection.contents.orEmpty().firstNotNullOfOrNull { it.gridRenderer }
        if (nestedGrid != null) return SectionListRenderer.Content(gridRenderer = nestedGrid)
        return SectionListRenderer.Content(
            musicShelfRenderer =
                MusicShelfRenderer(
                    title = null,
                    contents = itemSection.contents,
                    continuations = null,
                    bottomEndpoint = null,
                    moreContentButton = null,
                ),
        )
    }

    /** A playlist shelf read as the shelf shape the rest of this file works with. */
    private fun MusicPlaylistShelfRenderer.asSection(): SectionListRenderer.Content =
        SectionListRenderer.Content(
            musicShelfRenderer =
                MusicShelfRenderer(
                    title = null,
                    contents = contents,
                    continuations = null,
                    bottomEndpoint = null,
                    moreContentButton = null,
                ),
        )

    /**
     * The token a modern library grid appends as its last item, or null when it carries none.
     *
     * A grid can hand out its next page either through `continuations` or by appending a bare
     * `continuationItemRenderer` to `items`; this reads the latter.
     */
    private fun List<GridRenderer.Item>.trailingContinuation(): String? =
        lastOrNull()
            ?.continuationItemRenderer
            ?.continuationEndpoint
            ?.continuationCommand
            ?.token

    /** One slice of a library: its items, and the token to continue from when it has no own token. */
    private fun fromSlice(
        section: SectionListRenderer.Content?,
        fallbackContinuation: String?,
    ): LibraryContinuationPage {
        val grid = section?.gridRenderer
        if (grid != null) {
            return LibraryContinuationPage(
                items = grid.items.toLibraryItems(),
                continuation =
                    grid.continuations?.getContinuation()
                        ?: grid.items.trailingContinuation()
                        ?: fallbackContinuation,
            )
        }
        section?.musicPlaylistShelfRenderer?.let { shelf ->
            return LibraryContinuationPage(
                items = shelf.contents.toItems(),
                continuation = shelf.contents?.getContinuation() ?: fallbackContinuation,
            )
        }
        val shelf = section?.musicShelfRenderer
        return LibraryContinuationPage(
            items = shelf.toItems(),
            continuation =
                shelf?.continuations?.getContinuation()
                    ?: shelf?.contents?.getContinuation()
                    ?: fallbackContinuation,
        )
    }

    private fun List<GridRenderer.Item>.toLibraryItems(): List<YTItem> =
        mapNotNull { item ->
            item.musicTwoRowItemRenderer?.let { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                ?: item.lockupViewModel?.toLibraryItem()
        }

    private fun MusicShelfRenderer?.toItems(): List<YTItem> = this?.contents.toItems()

    private fun List<MusicShelfRenderer.Content>?.toItems(): List<YTItem> =
        this.orEmpty().flatMap { content ->
            val direct =
                listOfNotNull(
                    content.musicResponsiveListItemRenderer?.let { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                        ?: content.lockupViewModel?.toLibraryItem(),
                )
            // A section item can itself be a grid, which is how some libraries return their saved
            // playlists, albums and songs.
            direct +
                content.gridRenderer
                    ?.items
                    ?.toLibraryItems()
                    .orEmpty()
        }
}
