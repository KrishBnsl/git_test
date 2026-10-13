package com.gng.app.ui.icons

import androidx.annotation.DrawableRes
import com.gng.app.data.PlanKind

/** An icon a person can pick for a gang, a spot or a meal. The key is what gets stored in the database. */
data class IconChoice(val key: String, @DrawableRes val icon: Int, val label: String)

class IconSet(val choices: List<IconChoice>, private val fallbackKey: String) {
    val default: IconChoice = choices.first { it.key == fallbackKey }

    /** Unknown keys (including the emoji the MVP stored) resolve to the default. */
    fun resolve(key: String?): IconChoice = choices.firstOrNull { it.key == key } ?: default
}

val GroupIcons = IconSet(
    listOf(
        IconChoice("groups", GngIcons.Groups, "Gang"),
        IconChoice("local_fire_department", GngIcons.LocalFireDepartmentFill, "Fire"),
        IconChoice("restaurant", GngIcons.Restaurant, "Food"),
        IconChoice("celebration", GngIcons.Celebration, "Party"),
        IconChoice("sports_cricket", GngIcons.SportsCricket, "Cricket"),
        IconChoice("sports_esports", GngIcons.SportsEsports, "Gaming"),
        IconChoice("nightlife", GngIcons.Nightlife, "Nights"),
        IconChoice("rocket_launch", GngIcons.RocketLaunch, "Rocket"),
        IconChoice("coffee", GngIcons.Coffee, "Coffee"),
        IconChoice("school", GngIcons.School, "Study"),
        IconChoice("music_note", GngIcons.MusicNote, "Music"),
        IconChoice("pets", GngIcons.Pets, "Pets"),
    ),
    fallbackKey = "groups",
)

val ZoneIcons = IconSet(
    listOf(
        IconChoice("location_on", GngIcons.LocationOn, "Spot"),
        IconChoice("local_library", GngIcons.LocalLibrary, "Library"),
        IconChoice("restaurant", GngIcons.Restaurant, "Canteen"),
        IconChoice("coffee", GngIcons.Coffee, "Cafe"),
        IconChoice("home", GngIcons.Home, "Home"),
        IconChoice("apartment", GngIcons.Apartment, "Hostel"),
        IconChoice("sports_basketball", GngIcons.SportsBasketball, "Courts"),
        IconChoice("theater_comedy", GngIcons.TheaterComedy, "Auditorium"),
        IconChoice("science", GngIcons.Science, "Labs"),
        IconChoice("computer", GngIcons.Computer, "Computer centre"),
        IconChoice("park", GngIcons.Park, "Lawns"),
        IconChoice("school", GngIcons.School, "Campus"),
    ),
    fallbackKey = "location_on",
)

val FoodIcons = IconSet(
    listOf(
        IconChoice("lunch_dining", GngIcons.LunchDining, "Burger"),
        IconChoice("ramen_dining", GngIcons.RamenDining, "Noodles"),
        IconChoice("local_pizza", GngIcons.LocalPizza, "Pizza"),
        IconChoice("fastfood", GngIcons.Fastfood, "Fast food"),
        IconChoice("bakery_dining", GngIcons.BakeryDining, "Bakery"),
        IconChoice("set_meal", GngIcons.SetMeal, "Thali"),
        IconChoice("coffee", GngIcons.Coffee, "Chai or coffee"),
        IconChoice("icecream", GngIcons.Icecream, "Dessert"),
    ),
    fallbackKey = "lunch_dining",
)

@DrawableRes
fun PlanKind.iconRes(): Int = when (this) {
    PlanKind.Lunch -> GngIcons.Restaurant
    PlanKind.Snacks -> GngIcons.BakeryDining
    PlanKind.Outing -> GngIcons.DirectionsCar
    PlanKind.Study -> GngIcons.MenuBook
    PlanKind.Other -> GngIcons.AutoAwesome
}
