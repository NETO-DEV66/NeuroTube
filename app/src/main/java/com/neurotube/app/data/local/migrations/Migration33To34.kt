package com.neurotube.app.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Gives every library row an owner, so one account's playlists, watch history and channel groups
 * are invisible to the next.
 *
 * A single-column key cannot express "the same id in two accounts", so each table is rebuilt with
 * `accountId` in its primary key rather than altered in place. Existing rows keep their data and are
 * filed under the signed-out library ([GUEST]): they were written before accounts existed, and the
 * first account linked adopts that library wholesale (see `GuestLibraryAdopter`).
 *
 * `playlist_video_cross_ref` also widens its playlist foreign key to `(playlistId, accountId)`, so an
 * entry can only ever point at a playlist of its own account. Its `videos` foreign key is unchanged:
 * video metadata is shared, only playlist membership is owned.
 *
 * The `videos` table is deliberately left alone — it is a shared metadata cache, not a library.
 */
class Migration33To34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlists_new` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `thumbnailUrl` TEXT NOT NULL,
                `isPrivate` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `videoCount` INTEGER NOT NULL,
                `isMusic` INTEGER NOT NULL,
                `isUserCreated` INTEGER NOT NULL,
                `syncId` TEXT,
                `position` INTEGER NOT NULL DEFAULT 0,
                `accountId` TEXT NOT NULL DEFAULT 'guest',
                PRIMARY KEY(`id`, `accountId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `playlists_new`
                (`id`, `name`, `description`, `thumbnailUrl`, `isPrivate`, `createdAt`, `videoCount`, `isMusic`, `isUserCreated`, `syncId`, `position`, `accountId`)
            SELECT `id`, `name`, `description`, `thumbnailUrl`, `isPrivate`, `createdAt`, `videoCount`, `isMusic`, `isUserCreated`, `syncId`, `position`, 'guest'
            FROM `playlists`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `playlists`")
        db.execSQL("ALTER TABLE `playlists_new` RENAME TO `playlists`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_syncId` ON `playlists` (`syncId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_accountId` ON `playlists` (`accountId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlist_video_cross_ref_new` (
                `playlistId` TEXT NOT NULL,
                `videoId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `addedAt` INTEGER NOT NULL,
                `accountId` TEXT NOT NULL DEFAULT 'guest',
                PRIMARY KEY(`playlistId`, `videoId`, `accountId`),
                FOREIGN KEY(`playlistId`, `accountId`) REFERENCES `playlists`(`id`, `accountId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`videoId`) REFERENCES `videos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `playlist_video_cross_ref_new`
                (`playlistId`, `videoId`, `position`, `addedAt`, `accountId`)
            SELECT `playlistId`, `videoId`, `position`, `addedAt`, 'guest'
            FROM `playlist_video_cross_ref`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `playlist_video_cross_ref`")
        db.execSQL("ALTER TABLE `playlist_video_cross_ref_new` RENAME TO `playlist_video_cross_ref`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_video_cross_ref_videoId` ON `playlist_video_cross_ref` (`videoId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_video_cross_ref_playlistId` ON `playlist_video_cross_ref` (`playlistId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_video_cross_ref_accountId` ON `playlist_video_cross_ref` (`accountId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watch_history_new` (
                `videoId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `duration` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `thumbnailUrl` TEXT NOT NULL,
                `channelName` TEXT NOT NULL,
                `channelId` TEXT NOT NULL,
                `isMusic` INTEGER NOT NULL,
                `isShort` INTEGER NOT NULL,
                `isLocal` INTEGER NOT NULL,
                `accountId` TEXT NOT NULL DEFAULT 'guest',
                PRIMARY KEY(`videoId`, `accountId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `watch_history_new`
                (`videoId`, `position`, `duration`, `timestamp`, `title`, `thumbnailUrl`, `channelName`, `channelId`, `isMusic`, `isShort`, `isLocal`, `accountId`)
            SELECT `videoId`, `position`, `duration`, `timestamp`, `title`, `thumbnailUrl`, `channelName`, `channelId`, `isMusic`, `isShort`, `isLocal`, 'guest'
            FROM `watch_history`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `watch_history`")
        db.execSQL("ALTER TABLE `watch_history_new` RENAME TO `watch_history`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_timestamp` ON `watch_history` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_isMusic` ON `watch_history` (`isMusic`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_isShort` ON `watch_history` (`isShort`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_isLocal` ON `watch_history` (`isLocal`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_accountId` ON `watch_history` (`accountId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subscription_groups_new` (
                `name` TEXT NOT NULL,
                `channelIds` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `accountId` TEXT NOT NULL DEFAULT 'guest',
                PRIMARY KEY(`name`, `accountId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `subscription_groups_new`
                (`name`, `channelIds`, `sortOrder`, `accountId`)
            SELECT `name`, `channelIds`, `sortOrder`, 'guest'
            FROM `subscription_groups`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `subscription_groups`")
        db.execSQL("ALTER TABLE `subscription_groups_new` RENAME TO `subscription_groups`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_groups_accountId` ON `subscription_groups` (`accountId`)")
    }
}
