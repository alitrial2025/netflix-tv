package com.example.ui.screens.details

import com.example.data.TmdbBillboardMeta
import com.example.data.TmdbRepository
import com.example.model.Movie
import com.example.model.isKidSafeMovie

data class MovieExtraInfo(
    val studioTag: String,
    val genre: String,
    val ratingBadge: String,
    val triviaText: String,
    val reviewText: String,
    val reviewAuthor: String,
    val cast: String,
    val director: String,
    val writers: String,
    val moods: String
)

fun getMovieExtraInfo(movie: Movie, meta: TmdbBillboardMeta? = null): MovieExtraInfo {
    val base = when (movie.id) {
        "despicable_me_4" -> MovieExtraInfo(
            studioTag = "Illumination",
            genre = "Kids & Family • Animated Comedy • Adventure",
            ratingBadge = "PG",
            triviaText = "Gru's Mega Minions are inspired by classic comic book superheroes with wild superpowers!",
            reviewText = "Hilarious slapstick humor and delightful Minion chaos that kids will adore.",
            reviewAuthor = "Common Sense Media",
            cast = "Steve Carell, Kristen Wiig, Will Ferrell, Sofia Vergara",
            director = "Chris Renaud",
            writers = "Mike White, Ken Daurio",
            moods = "Hilarious, Cheerful, Action-Packed"
        )
        "inside_out_2" -> MovieExtraInfo(
            studioTag = "Disney • Pixar",
            genre = "Kids & Family • Animated Fantasy • Comedy",
            ratingBadge = "PG",
            triviaText = "Anxiety's wild orange hair was specially designed by Pixar artists to convey her lightning-fast energy.",
            reviewText = "A heartfelt, emotional triumph that connects brilliantly with kids and parents.",
            reviewAuthor = "Kids First!",
            cast = "Amy Poehler, Maya Hawke, Phyllis Smith, Lewis Black",
            director = "Kelsey Mann",
            writers = "Meg LeFauve, Dave Holstein",
            moods = "Heartwarming, Feel-Good, Imaginative"
        )
        "pokemon_horizons" -> MovieExtraInfo(
            studioTag = "The Pokémon Company",
            genre = "Kids TV • Animated Series • Adventure",
            ratingBadge = "TV-Y7",
            triviaText = "Liko's mysterious pendant and Roy's ancient Poké Ball hold ancient secrets of the Legendary Pokémon!",
            reviewText = "An exciting fresh chapter in the Pokémon world with charming heroes and amazing battles.",
            reviewAuthor = "Anime News Network",
            cast = "Minori Suzuki, Yuka Terasaki, Taku Yashiro, Ikue Otani",
            director = "Saori Den",
            writers = "Dai Sato",
            moods = "Exciting, Magical, Adventure"
        )
        "one_piece_kids" -> MovieExtraInfo(
            studioTag = "Toei Animation",
            genre = "Kids TV • Animated Series • Pirate Adventure",
            ratingBadge = "12",
            triviaText = "Luffy's dream is to find the legendary One Piece treasure and become King of the Pirates!",
            reviewText = "An unforgettable journey of friendship, courage, and boundless adventure across the seas.",
            reviewAuthor = "IGN Kids",
            cast = "Mayumi Tanaka, Akemi Okamura, Kazuya Nakai, Kappei Yamaguchi",
            director = "Konosuke Uda",
            writers = "Eiichiro Oda",
            moods = "Epic, Heroic, Fun"
        )
        "jujutsu_kaisen" -> MovieExtraInfo(
            studioTag = "Mappa Studios",
            genre = "Anime • Dark Fantasy",
            ratingBadge = "18",
            triviaText = "Want to know the secrets of the upcoming Culling Game and cursed spirits?",
            reviewText = "Breathtaking animation and intense supernatural battles!",
            reviewAuthor = "Anime News Network",
            cast = "Junya Enoki, Yuma Uchida, Asami Seto, Yuichi Nakamura",
            director = "Sunghoo Park",
            writers = "Gege Akutami",
            moods = "Action-Packed, Dark, Supernatural"
        )
        "101" -> MovieExtraInfo(
            studioTag = "Netflix Original",
            genre = "Sci-Fi • Horror • Drama",
            ratingBadge = "16",
            triviaText = "Curious about how they designed the horrifying Demogorgon from the Upside Down?",
            reviewText = "A loving, nostalgic tribute to Spielberg and 80s genre films.",
            reviewAuthor = "IGN",
            cast = "Winona Ryder, David Harbour, Millie Bobby Brown, Finn Wolfhard",
            director = "The Duffer Brothers",
            writers = "Matt Duffer, Ross Duffer",
            moods = "Suspenseful, Nostalgic, Sci-Fi"
        )
        "102" -> MovieExtraInfo(
            studioTag = "Legendary Pictures",
            genre = "Sci-Fi • Adventure • Epic",
            ratingBadge = "16",
            triviaText = "Curious about the constructed language of the Fremen and sandworm visual effects?",
            reviewText = "An absolute masterpiece of modern science fiction cinema.",
            reviewAuthor = "Variety",
            cast = "Timothée Chalamet, Zendaya, Rebecca Ferguson, Josh Brolin",
            director = "Denis Villeneuve",
            writers = "Denis Villeneuve, Jon Spaihts",
            moods = "Epic, Immersive, Mind-Bending"
        )
        "103" -> MovieExtraInfo(
            studioTag = "Riot Games",
            genre = "Action • Sci-Fi • Animation",
            ratingBadge = "18",
            triviaText = "Discover the hidden League of Legends lore and Easter eggs inside Piltover & Zaun.",
            reviewText = "Visually spectacular and emotionally devastating storytelling.",
            reviewAuthor = "Collider",
            cast = "Hailee Steinfeld, Ella Purnell, Kevin Alejandro, Reed Shannon",
            director = "Pascal Charrue, Arnaud Delord",
            writers = "Christian Linke, Alex Yee",
            moods = "Emotional, Steampunk, Thrilling"
        )
        "104" -> MovieExtraInfo(
            studioTag = "Universal Pictures",
            genre = "Biography • Drama • History",
            ratingBadge = "18",
            triviaText = "Did you know Nolan recreated the Trinity nuclear test entirely without CGI effects?",
            reviewText = "Cillian Murphy delivers the towering performance of a lifetime.",
            reviewAuthor = "The Guardian",
            cast = "Cillian Murphy, Emily Blunt, Matt Damon, Robert Downey Jr.",
            director = "Christopher Nolan",
            writers = "Christopher Nolan",
            moods = "Intense, Historical, Intellectual"
        )
        else -> {
            val isKid = isKidSafeMovie(movie)
            val isSeries = movie.type.equals("Series", ignoreCase = true) || movie.duration.contains("Season", ignoreCase = true)

            if (isKid) {
                if (isSeries) {
                    MovieExtraInfo(
                        studioTag = "Netflix Kids",
                        genre = "Kids TV • Animated Series • Adventure",
                        ratingBadge = movie.rating.ifBlank { "TV-Y7" },
                        triviaText = "Discover fun facts and secret adventures from the world of ${movie.title}!",
                        reviewText = "A colorful, exciting series packed with laughs and teamwork for kids!",
                        reviewAuthor = "Kids First!",
                        cast = "Voice Cast, Animated Characters",
                        director = "Animation Director",
                        writers = "Series Writers",
                        moods = "Cheerful, Exciting, Fun"
                    )
                } else {
                    MovieExtraInfo(
                        studioTag = if (movie.type == "Animation") "Animation Studio" else "Family Cinema",
                        genre = "Kids & Family • Animated Film • Adventure",
                        ratingBadge = movie.rating.ifBlank { "PG" },
                        triviaText = "Want to discover behind-the-scenes secrets of how ${movie.title} was animated?",
                        reviewText = "A heartwarming, beautifully animated film that the whole family will love.",
                        reviewAuthor = "Common Sense Media",
                        cast = "Voice Cast, Lead Characters",
                        director = "Feature Director",
                        writers = "Screenplay Writers",
                        moods = "Heartwarming, Magical, Fun"
                    )
                }
            } else if (movie.title.contains("Spider", ignoreCase = true) || movie.title.contains("Home", ignoreCase = true)) {
                MovieExtraInfo(
                    studioTag = "Marvel Studios",
                    genre = "Action • Adventure • Fantasy",
                    ratingBadge = "PG-13",
                    triviaText = "Want to know what happens after \"Avengers: Infinity War\"?",
                    reviewText = "Light, bright and wildly entertaining.",
                    reviewAuthor = "Chicago Sun-Times",
                    cast = "Tom Holland, Samuel L. Jackson, Zendaya, Cobie Smulders, Jon Favreau",
                    director = "Jon Watts",
                    writers = "Chris McKenna, Erik Sommers",
                    moods = "Exciting, Mind-Bending, Action-Packed"
                )
            } else {
                val fallbackGenres = movie.type.ifBlank { "Drama" }
                MovieExtraInfo(
                    studioTag = "Netflix Original",
                    genre = (if (isSeries) "TV Series" else "Feature Film") + " • " + fallbackGenres,
                    ratingBadge = movie.rating.ifBlank { "16" },
                    triviaText = "Want to explore exclusive behind-the-scenes diaries and cast interviews?",
                    reviewText = "A beautiful, gripping story that keeps you hooked from start to finish.",
                    reviewAuthor = "Rotten Tomatoes",
                    cast = "Lead Actor, Supporting Actor, Ensemble Cast",
                    director = "Featured Director",
                    writers = "Original Writers",
                    moods = "Captivating, Entertaining, Polished"
                )
            }
        }
    }

    if (meta == null) return base

    val dynamicGenres = meta.genreNames
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(" • ")
        .ifBlank { base.genre }

    val dynamicMoods = meta.keywords
        .filter { it.length in 3..22 }
        .take(4)
        .joinToString(", ") { kw ->
            kw.split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        }
        .ifBlank { base.moods }

    val cleanCollection = TmdbRepository.cleanFranchiseCollectionName(meta.collectionName)
    val dynamicTrivia = when {
        !meta.sequelOfTitle.isNullOrBlank() ->
            "Continues the story after ${meta.sequelOfTitle} in the ${cleanCollection ?: movie.title} saga."
        !cleanCollection.isNullOrBlank() ->
            "Part of the acclaimed $cleanCollection franchise universe."
        else -> base.triviaText
    }

    return base.copy(
        genre = dynamicGenres,
        moods = dynamicMoods,
        triviaText = dynamicTrivia
    )
}
