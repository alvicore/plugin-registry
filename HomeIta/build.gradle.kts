// use an integer for version numbers
version = 4

cloudstream {
    description = "Home personale guidata da AniList: tendenze, stagione in corso, in onda"
    authors = listOf("vito")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    requiresResources = false
    language = "it"

    iconUrl = "https://anilist.co/img/icons/favicon-32x32.png"
}

dependencies {
    // Mutex per serializzare il ripiego Jikan. A runtime le coroutine le fornisce l'app,
    // quindi compileOnly: dentro il .cs3 non deve finirci una seconda copia.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
