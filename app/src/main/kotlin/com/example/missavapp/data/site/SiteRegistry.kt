package com.example.missavapp.data.site

import com.example.missavapp.data.model.SiteProfile

object SiteRegistry {
    val missav = SiteProfile(
        id = "missav",
        name = "MissAV",
        hosts = listOf("https://missav.ai", "https://missav.ws", "https://missav.live", "https://missav123.com"),
        locale = "cn",
        enabled = true,
        note = null
    )

    val jable = SiteProfile(
        id = "jable",
        name = "Jable.tv",
        hosts = listOf("https://jable.tv"),
        locale = "en",
        enabled = true,
        note = null
    )

    val av01 = SiteProfile(
        id = "av01",
        name = "AV01",
        hosts = listOf("https://www.av01.media"),
        locale = "jp",
        enabled = true,
        note = null
    )

    val av123 = SiteProfile(
        id = "123av",
        name = "NJAV / 123AV",
        hosts = listOf("https://123av.com", "https://njav.tv"),
        locale = "zh",
        enabled = true,
        note = null
    )

    val all = listOf(missav, jable, av01, av123)
}
