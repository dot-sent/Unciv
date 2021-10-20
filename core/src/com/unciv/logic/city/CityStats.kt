package com.unciv.logic.city

import com.unciv.UncivGame
import com.unciv.logic.civilization.CityStateType
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.RoadStatus
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ModOptionsConstants
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.utils.toPercent
import kotlin.math.min


/** Holds and calculates [Stats] for a city.
 * 
 * No field needs to be saved, all are calculated on the fly,
 * so its field in [CityInfo] is @Transient and no such annotation is needed here.
 */
class CityStats(val cityInfo: CityInfo) {
    //region Fields, Transient 

    var baseStatList = LinkedHashMap<String, Stats>()

    var statPercentBonusList = LinkedHashMap<String, Stats>()

    // Computed from baseStatList and statPercentBonusList - this is so the players can see a breakdown
    var finalStatList = LinkedHashMap<String, Stats>()

    var happinessList = LinkedHashMap<String, Float>()

    var foodEaten = 0f

    var currentCityStats: Stats = Stats()  // This is so we won't have to calculate this multiple times - takes a lot of time, especially on phones

    //endregion
    //region Pure Functions

    private fun getStatsFromTiles(): Stats {
        val stats = Stats()
        for (cell in cityInfo.tilesInRange
                .filter { cityInfo.location == it.position || cityInfo.isWorked(it) ||
                        it.owningCity == cityInfo && (it.getTileImprovement()?.hasUnique(UniqueType.TileProvidesYieldWithoutPopulation) == true ||
                            it.hasUnique(UniqueType.TileProvidesYieldWithoutPopulation))
                })
            stats.add(cell.getTileStats(cityInfo, cityInfo.civInfo))
        return stats
    }

    private fun getStatsFromTradeRoute(): Stats {
        val stats = Stats()
        if (!cityInfo.isCapital() && cityInfo.isConnectedToCapital()) {
            val civInfo = cityInfo.civInfo
            stats.gold = civInfo.getCapital().population.population * 0.15f + cityInfo.population.population * 1.1f - 1 // Calculated by http://civilization.wikia.com/wiki/Trade_route_(Civ5)
            for (unique in cityInfo.getMatchingUniques("[] from each Trade Route"))
                stats.add(unique.stats)
            if (civInfo.hasUnique("Gold from all trade routes +25%")) stats.gold *= 1.25f // Machu Picchu speciality
        }
        return stats
    }

    private fun getStatsFromProduction(production: Float): Stats {
        val stats = Stats()

        when (cityInfo.cityConstructions.currentConstructionFromQueue) {
            "Gold" -> stats.gold += production / 4
            "Science" -> stats.science += production * getScienceConversionRate()
        }
        return stats
    }

    fun getScienceConversionRate(): Float {
        var conversionRate = 1 / 4f
        if (cityInfo.civInfo.hasUnique("Production to science conversion in cities increased by 33%"))
            conversionRate *= 1.33f
        return conversionRate
    }

    private fun getStatPercentBonusesFromRailroad(): Stats {
        val stats = Stats()
        val railroadImprovement = RoadStatus.Railroad.improvement(cityInfo.getRuleset())
            ?: return stats // for mods
        val techEnablingRailroad = railroadImprovement.techRequired
        // If we conquered enemy cities connected by railroad, but we don't yet have that tech,
        // we shouldn't get bonuses, it's as if the tracks are laid out but we can't operate them.
        if ( (techEnablingRailroad == null || cityInfo.civInfo.tech.isResearched(techEnablingRailroad))
                && (cityInfo.isCapital() || isConnectedToCapital(RoadStatus.Railroad)))
            stats.production += 25f
        return stats
    }

    @Deprecated("As of 3.16.16 - replaced by regular getStatPercentBonusesFromUniques()")
    private fun getStatPercentBonusesFromResources(construction: IConstruction): Stats {
        val stats = Stats()

        if (construction is Building
                && construction.isWonder
                && cityInfo.civInfo.getCivResources()
                        .any { it.amount > 0 && it.resource.unique == "+15% production towards Wonder construction" })
            stats.production += 15f

        return stats
    }

    private fun getStatsFromNationUnique(): Stats {
        return getStatsFromUniques(cityInfo.civInfo.nation.uniqueObjects.asSequence())
    }

    private fun getStatsFromCityStates(): Stats {
        val stats = Stats()

        for (otherCiv in cityInfo.civInfo.getKnownCivs()) {
            val relationshipLevel = otherCiv.getDiplomacyManager(cityInfo.civInfo).relationshipLevel()
            if (otherCiv.isCityState() && relationshipLevel >= RelationshipLevel.Friend) {
                val eraInfo = cityInfo.civInfo.getEra()

                if (eraInfo.undefinedCityStateBonuses()) {
                    // Deprecated, assume Civ V values for compatibility
                    if (otherCiv.cityStateType == CityStateType.Maritime && relationshipLevel == RelationshipLevel.Ally)
                        stats.food += 1
                    if (otherCiv.cityStateType == CityStateType.Maritime && cityInfo.isCapital())
                        stats.food += 2
                } else {
                    for (bonus in eraInfo.getCityStateBonuses(otherCiv.cityStateType, relationshipLevel)) {
                        if (bonus.isOfType(UniqueType.CityStateStatsPerCity) 
                            && cityInfo.matchesFilter(bonus.params[1]) 
                            && bonus.conditionalsApply(otherCiv, cityInfo) 
                        ) stats.add(bonus.stats)
                    }
                }

                for (unique in cityInfo.civInfo.getMatchingUniques(UniqueType.BonusStatsFromCityStates)) {
                    stats[Stat.valueOf(unique.params[1])] *= unique.params[0].toPercent()
                }
            }
        }

        return stats
    }

    private fun getStatPercentBonusesFromNationUnique(currentConstruction: IConstruction): Stats {
        val stats = Stats()

        stats.add(getStatPercentBonusesFromUniques(currentConstruction, cityInfo.civInfo.nation.uniqueObjects.asSequence()))

        if (currentConstruction is Building
            && cityInfo.civInfo.cities.isNotEmpty()
            && cityInfo.civInfo.getCapital().cityConstructions.builtBuildings.contains(currentConstruction.name)
            && cityInfo.civInfo.hasUnique("+25% Production towards any buildings that already exist in the Capital")
        ) {
            stats.production += 25f
        }

        return stats
    }

    private fun getStatPercentBonusesFromPuppetCity(): Stats {
        val stats = Stats()
        if (cityInfo.isPuppet) {
            stats.science -= 25f
            stats.culture -= 25f
        }
        return stats
    }

    private fun getGrowthBonusFromPoliciesAndWonders(): Float {
        var bonus = 0f
        // "[amount]% growth [cityFilter]"
        for (unique in cityInfo.getMatchingUniques(UniqueType.GrowthPercentBonus)) {
            if (!unique.conditionalsApply(cityInfo.civInfo, cityInfo)) continue
            if (cityInfo.matchesFilter(unique.params[1]))
                bonus += unique.params[0].toFloat()
        }
        return bonus / 100
    }

    fun hasExtraAnnexUnhappiness(): Boolean {
        if (cityInfo.civInfo.civName == cityInfo.foundingCiv || cityInfo.isPuppet) return false
        return !cityInfo.containsBuildingUnique(UniqueType.RemoveAnnexUnhappiness)
    }

    fun getStatsOfSpecialist(specialistName: String): Stats {
        val specialist = cityInfo.getRuleset().specialists[specialistName]
            ?: return Stats()
        val stats = specialist.clone()
        for (unique in cityInfo.getMatchingUniques(UniqueType.StatsFromSpecialist))
            if (cityInfo.matchesFilter(unique.params[1]))
                stats.add(unique.stats)
        for (unique in cityInfo.civInfo.getMatchingUniques("[] from every []"))
            if (unique.params[1] == specialistName)
                stats.add(unique.stats)
        return stats
    }

    private fun getStatsFromSpecialists(specialists: Counter<String>): Stats {
        val stats = Stats()
        for (entry in specialists.filter { it.value > 0 })
            stats.add(getStatsOfSpecialist(entry.key) * entry.value)
        return stats
    }

    private fun getStatsFromUniques(uniques: Sequence<Unique>): Stats {
        val stats = Stats()

        for (unique in uniques.toList()) { // Should help mitigate getConstructionButtonDTOs concurrency problems.
            if (unique.isOfType(UniqueType.Stats) && unique.conditionalsApply(cityInfo.civInfo, cityInfo)) {
                stats.add(unique.stats)
            }

            if (unique.isOfType(UniqueType.StatsPerCity) 
                && cityInfo.matchesFilter(unique.params[1]) 
                && unique.conditionalsApply(cityInfo.civInfo, cityInfo)
            ) {
                stats.add(unique.stats)
            }

            // "[stats] per [amount] population [cityFilter]"
            if (unique.isOfType(UniqueType.StatsPerPopulation) && cityInfo.matchesFilter(unique.params[2])) {
                val amountOfEffects = (cityInfo.population.population / unique.params[1].toInt()).toFloat()
                stats.add(unique.stats.times(amountOfEffects))
            }

            // "[stats] in cities with [amount] or more population
            if (unique.placeholderText == "[] in cities with [] or more population" && cityInfo.population.population >= unique.params[1].toInt())
                stats.add(unique.stats)

            // "[stats] in cities on [tileFilter] tiles"
            if (unique.placeholderText == "[] in cities on [] tiles" && cityInfo.getCenterTile().matchesTerrainFilter(unique.params[1]))
                stats.add(unique.stats)

            if (unique.placeholderText == "[] per turn from cities before []" && !cityInfo.civInfo.hasTechOrPolicy(unique.params[1]))
                stats.add(unique.stats)
        }

        return stats
    }

    private fun getStatPercentBonusesFromGoldenAge(isGoldenAge: Boolean): Stats {
        val stats = Stats()
        if (isGoldenAge) {
            stats.production += 20f
            stats.culture += 20f
        }
        return stats
    }

    private fun getStatPercentBonusesFromUniques(currentConstruction: IConstruction, uniqueSequence: Sequence<Unique>): Stats {
        val stats = Stats()
        val uniques = uniqueSequence.toList().asSequence()
          // Since this is sometimes run from a different thread (getConstructionButtonDTOs),
          // this helps mitigate concurrency problems.
        
        for (unique in uniques.filter { it.isOfType(UniqueType.StatPercentBonus) }) {
            if (!unique.conditionalsApply(cityInfo.civInfo, cityInfo)) continue
            stats.add(Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }
        
        // Deprecated since 3.17.0
            // For instance "+[50]% [Production]
            for (unique in uniques.filter { it.placeholderText == "+[]% [] in all cities"})
                stats.add(Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        //

        // Deprecated since 3.17.10
            // Params: "+[amount]% [Stat] [cityFilter]", pretty crazy amirite
            // For instance "+[50]% [Production] [in all cities]
            for (unique in uniques.filter { it.isOfType(UniqueType.StatPercentBonusCitiesDeprecated) })
                if (cityInfo.matchesFilter(unique.params[2]))
                    stats.add(Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        //
        
        for (unique in uniques.filter { it.isOfType(UniqueType.StatPercentBonusCities) }) {
            if (!unique.conditionalsApply(StateForConditionals(civInfo = cityInfo.civInfo, cityInfo = cityInfo))) continue
            if (cityInfo.matchesFilter(unique.params[2]))
                stats.add(Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }
        
        val uniquesToCheck =
            if (currentConstruction is Building && !currentConstruction.isAnyWonder()) {
                uniques.filter { it.isOfType(UniqueType.PercentProductionWonders) }   
            } else if (currentConstruction is Building && currentConstruction.isAnyWonder()) {
                uniques.filter { it.isOfType(UniqueType.PercentProductionBuildings) }
            } else if (currentConstruction is BaseUnit) {
                uniques.filter { it.isOfType(UniqueType.PercentProductionUnits) }
            } else { // Science/Gold production
                sequenceOf()
            }
        for (unique in uniquesToCheck) {
            if (!unique.conditionalsApply(StateForConditionals(civInfo = cityInfo.civInfo, cityInfo = cityInfo))) continue
            if (constructionMatchesFilter(currentConstruction, unique.params[1]) && cityInfo.matchesFilter(unique.params[2]))
                stats.production += unique.params[0].toInt()
        }

        // Deprecated since 3.17.10
            if (currentConstruction is Building && !currentConstruction.isAnyWonder())
                for (unique in uniques.filter { it.isOfType(UniqueType.PercentProductionStatBuildings) }) {
                    val stat = Stat.valueOf(unique.params[1])
                    if (currentConstruction.isStatRelated(stat))
                        stats.production += unique.params[0].toInt()
                }
            for (unique in uniques.filter { it.isOfType(UniqueType.PercentProductionConstructions) }) {
                if (constructionMatchesFilter(currentConstruction, unique.params[1]))
                    stats.production += unique.params[0].toInt()
            }
            // Used for specific buildings (e.g. +100% Production when constructing a Factory)
            for (unique in uniques.filter { it.isOfType(UniqueType.PercentProductionBuildingName) }) {
                if (constructionMatchesFilter(currentConstruction, unique.params[1]))
                    stats.production += unique.params[0].toInt()
            }
    
            //  "+[amount]% Production when constructing [constructionFilter] [cityFilter]"
            for (unique in uniques.filter { it.isOfType(UniqueType.PercentProductionConstructionsCities) }) {
                if (constructionMatchesFilter(currentConstruction, unique.params[1]) && cityInfo.matchesFilter(unique.params[2]))
                    stats.production += unique.params[0].toInt()
            }
    
            // "+[amount]% Production when constructing [unitFilter] units [cityFilter]"
            for (unique in uniques.filter { it.isOfType(UniqueType.PercentProductionUnitsDeprecated) }) {
                if (constructionMatchesFilter(currentConstruction, unique.params[1]) && cityInfo.matchesFilter(unique.params[2]))
                    stats.production += unique.params[0].toInt()
            }
        //

        // Deprecated since 3.17.1
            if (cityInfo.civInfo.getHappiness() >= 0) {
                for (unique in uniques.filter { it.placeholderText == "[]% [] while the empire is happy"})
                    stats.add(Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
            }
        //
        
        for (unique in uniques.filter { it.placeholderText == "[]% [] from every follower, up to []%" })
            stats.add(
                Stat.valueOf(unique.params[1]), 
                min(
                    unique.params[0].toFloat() * cityInfo.religion.getFollowersOfMajorityReligion(), 
                    unique.params[2].toFloat()
                )
            )

        return stats
    }

    private fun getStatPercentBonusesFromUnitSupply(): Stats {
        val stats = Stats()
        val supplyDeficit = cityInfo.civInfo.stats().getUnitSupplyDeficit()
        if (supplyDeficit > 0)
            stats.production = cityInfo.civInfo.stats().getUnitSupplyProductionPenalty()
        return stats
    }

    private fun constructionMatchesFilter(construction: IConstruction, filter: String): Boolean {
        if (construction is Building) return construction.matchesFilter(filter)
        if (construction is BaseUnit) return construction.matchesFilter(filter)
        return false
    }

    fun isConnectedToCapital(roadType: RoadStatus): Boolean {
        if (cityInfo.civInfo.cities.count() < 2) return false// first city!

        // Railroad, or harbor from railroad
        return if (roadType == RoadStatus.Railroad) 
                cityInfo.isConnectedToCapital { 
                    roadTypes ->
                    roadTypes.any { it.contains(RoadStatus.Railroad.name) }
                }
            else cityInfo.isConnectedToCapital()
    }

    private fun getBuildingMaintenanceCosts(citySpecificUniques: Sequence<Unique>): Float {
        // Same here - will have a different UI display.
        var buildingsMaintenance = cityInfo.cityConstructions.getMaintenanceCosts().toFloat() // this is AFTER the bonus calculation!
        if (!cityInfo.civInfo.isPlayerCivilization()) {
            buildingsMaintenance *= cityInfo.civInfo.gameInfo.getDifficulty().aiBuildingMaintenanceModifier
        }

        // e.g. "-[50]% maintenance costs for buildings [in this city]"
        for (unique in cityInfo.getMatchingUniques("-[]% maintenance cost for buildings []", citySpecificUniques)) {
            buildingsMaintenance *= (1f - unique.params[0].toFloat() / 100)
        }

        return buildingsMaintenance
    }

    //endregion
    //region State-Changing Methods

    // needs to be a separate function because we need to know the global happiness state
    // in order to determine how much food is produced in a city!
    fun updateCityHappiness() {
        val civInfo = cityInfo.civInfo
        val newHappinessList = LinkedHashMap<String, Float>()
        var unhappinessModifier = civInfo.getDifficulty().unhappinessModifier
        if (!civInfo.isPlayerCivilization())
            unhappinessModifier *= civInfo.gameInfo.getDifficulty().aiUnhappinessModifier

        var unhappinessFromCity = -3f // -3 happiness per city
        if (civInfo.hasUnique("Unhappiness from number of Cities doubled"))
            unhappinessFromCity *= 2f //doubled for the Indian

        newHappinessList["Cities"] = unhappinessFromCity * unhappinessModifier

        var unhappinessFromCitizens = cityInfo.population.population.toFloat()
        var unhappinessFromSpecialists = cityInfo.population.getNumberOfSpecialists().toFloat()

        // Deprecated since 3.16.11
            for (unique in civInfo.getMatchingUniques("Specialists only produce []% of normal unhappiness"))
                unhappinessFromSpecialists *= (1f - unique.params[0].toFloat() / 100f)
        //

        for (unique in cityInfo.getMatchingUniques("[]% unhappiness from specialists []")) {
            if (cityInfo.matchesFilter(unique.params[1]))
                unhappinessFromSpecialists *= unique.params[0].toPercent()
        }

        unhappinessFromCitizens -= cityInfo.population.getNumberOfSpecialists().toFloat() - unhappinessFromSpecialists

        if (cityInfo.isPuppet)
            unhappinessFromCitizens *= 1.5f
        else if (hasExtraAnnexUnhappiness())
            unhappinessFromCitizens *= 2f

        for (unique in cityInfo.getMatchingUniques(UniqueType.UnhappinessFromPopulationPercentageChange))
            if (cityInfo.matchesFilter(unique.params[1]))
                unhappinessFromCitizens *= unique.params[0].toPercent()

        newHappinessList["Population"] = -unhappinessFromCitizens * unhappinessModifier

        val happinessFromPolicies = getStatsFromUniques(civInfo.policies.policyUniques.getAllUniques()).happiness

        newHappinessList["Policies"] = happinessFromPolicies

        if (hasExtraAnnexUnhappiness()) newHappinessList["Occupied City"] = -2f //annexed city

        val happinessFromSpecialists = getStatsFromSpecialists(cityInfo.population.getNewSpecialists()).happiness.toInt().toFloat()
        if (happinessFromSpecialists > 0) newHappinessList["Specialists"] = happinessFromSpecialists

        val happinessFromBuildings = cityInfo.cityConstructions.getStats().happiness.toInt().toFloat()
        newHappinessList["Buildings"] = happinessFromBuildings

        newHappinessList["National ability"] = getStatsFromUniques(cityInfo.civInfo.nation.uniqueObjects.asSequence()).happiness

        newHappinessList["Wonders"] = getStatsFromUniques(civInfo.getCivWideBuildingUniques(cityInfo)).happiness

        newHappinessList["Religion"] = getStatsFromUniques(cityInfo.religion.getUniques()).happiness

        newHappinessList["Tile yields"] = getStatsFromTiles().happiness

        // we don't want to modify the existing happiness list because that leads
        // to concurrency problems if we iterate on it while changing
        happinessList = newHappinessList
    }

    private fun updateBaseStatList() {
        val newBaseStatList = LinkedHashMap<String, Stats>() // we don't edit the existing baseStatList directly, in order to avoid concurrency exceptions
        val civInfo = cityInfo.civInfo

        newBaseStatList["Population"] = Stats(
            science = cityInfo.population.population.toFloat(),
            production = cityInfo.population.getFreePopulation().toFloat()
        )
        newBaseStatList["Tile yields"] = getStatsFromTiles()
        newBaseStatList["Specialists"] = getStatsFromSpecialists(cityInfo.population.getNewSpecialists())
        newBaseStatList["Trade routes"] = getStatsFromTradeRoute()
        newBaseStatList["Buildings"] = cityInfo.cityConstructions.getStats()
        newBaseStatList["Policies"] = getStatsFromUniques(civInfo.policies.policyUniques.getAllUniques())
        newBaseStatList["National ability"] = getStatsFromNationUnique()
        newBaseStatList["Wonders"] = getStatsFromUniques(civInfo.getCivWideBuildingUniques(cityInfo))
        newBaseStatList["City-States"] = getStatsFromCityStates()
        newBaseStatList["Religion"] = getStatsFromUniques(cityInfo.religion.getUniques())

        baseStatList = newBaseStatList
    }


    private fun updateStatPercentBonusList(currentConstruction: IConstruction, localBuildingUniques: Sequence<Unique>) {
        val newStatPercentBonusList = LinkedHashMap<String, Stats>()
        newStatPercentBonusList["Golden Age"] = getStatPercentBonusesFromGoldenAge(cityInfo.civInfo.goldenAges.isGoldenAge())
        newStatPercentBonusList["Policies"] = getStatPercentBonusesFromUniques(currentConstruction, cityInfo.civInfo.policies.policyUniques.getAllUniques())
        newStatPercentBonusList["Buildings"] = getStatPercentBonusesFromUniques(currentConstruction, localBuildingUniques)
                .plus(cityInfo.cityConstructions.getStatPercentBonuses()) // This function is to be deprecated but it'll take a while.
        newStatPercentBonusList["Wonders"] = getStatPercentBonusesFromUniques(currentConstruction, cityInfo.civInfo.getCivWideBuildingUniques(cityInfo))
        newStatPercentBonusList["Railroads"] = getStatPercentBonusesFromRailroad()  // Name chosen same as tech, for translation, but theoretically independent
        val resourceUniques = cityInfo.civInfo.getCivResources().asSequence().flatMap { it.resource.uniqueObjects }
        newStatPercentBonusList["Resources"] = getStatPercentBonusesFromUniques(currentConstruction, resourceUniques)
        // Deprecated as of 3.16.16
        newStatPercentBonusList["Resources"] = getStatPercentBonusesFromResources(currentConstruction)
        newStatPercentBonusList["National ability"] = getStatPercentBonusesFromNationUnique(currentConstruction)
        newStatPercentBonusList["Puppet City"] = getStatPercentBonusesFromPuppetCity()
        newStatPercentBonusList["Religion"] = getStatPercentBonusesFromUniques(currentConstruction, cityInfo.religion.getUniques())
        newStatPercentBonusList["Unit Supply"] = getStatPercentBonusesFromUnitSupply()

        if (UncivGame.Current.superchargedForDebug) {
            val stats = Stats()
            for (stat in Stat.values()) stats[stat] = 10000f
            newStatPercentBonusList["Supercharged"] = stats
        }

        statPercentBonusList = newStatPercentBonusList
    }

    fun update(currentConstruction: IConstruction = cityInfo.cityConstructions.getCurrentConstruction()) {
        // We calculate this here for concurrency reasons
        // If something needs this, we pass this through as a parameter
        val localBuildingUniques = cityInfo.cityConstructions.builtBuildingUniqueMap.getAllUniques()
        
        // Is This line really necessary? There is only a single unique that actually uses this, 
        // and it is passed to functions at least 3 times for that
        // It's the only reason `cityInfo.getMatchingUniques` has a localUniques parameter,
        // which clutters readability, and also the only reason `CityInfo.getAllLocalUniques()`
        // exists in the first place, though that could be useful for the previous line too.
        val citySpecificUniques = cityInfo.getAllLocalUniques()

        // We need to compute Tile yields before happiness
        updateBaseStatList()
        updateCityHappiness()
        updateStatPercentBonusList(currentConstruction, localBuildingUniques)

        updateFinalStatList(currentConstruction, citySpecificUniques) // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        val newCurrentCityStats = Stats()
        for (stat in finalStatList.values) newCurrentCityStats.add(stat)
        currentCityStats = newCurrentCityStats

        cityInfo.civInfo.updateStatsForNextTurn()
    }

    private fun updateFinalStatList(currentConstruction: IConstruction, citySpecificUniques: Sequence<Unique>) {
        val newFinalStatList = LinkedHashMap<String, Stats>() // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        for (entry in baseStatList)
            newFinalStatList[entry.key] = entry.value.clone()

        val statPercentBonusesSum = Stats()
        for (bonus in statPercentBonusList.values) statPercentBonusesSum.add(bonus)

        for (entry in newFinalStatList.values)
            entry.production *= statPercentBonusesSum.production.toPercent()

        val statsFromProduction = getStatsFromProduction(newFinalStatList.values.map { it.production }.sum())
        baseStatList = LinkedHashMap(baseStatList).apply { put("Construction", statsFromProduction) } // concurrency-safe addition
        newFinalStatList["Construction"] = statsFromProduction

        val isUnhappy = cityInfo.civInfo.getHappiness() < 0
        for (entry in newFinalStatList.values) {
            entry.gold *= statPercentBonusesSum.gold.toPercent()
            entry.culture *= statPercentBonusesSum.culture.toPercent()
            entry.food *= statPercentBonusesSum.food.toPercent()
        }

        // AFTER we've gotten all the gold stats figured out, only THEN do we plonk that gold into Science
        if (cityInfo.getRuleset().modOptions.uniques.contains(ModOptionsConstants.convertGoldToScience)) {
            val amountConverted = (newFinalStatList.values.sumOf { it.gold.toDouble() }
                    * cityInfo.civInfo.tech.goldPercentConvertedToScience).toInt().toFloat()
            if (amountConverted > 0) // Don't want you converting negative gold to negative science yaknow
                newFinalStatList["Gold -> Science"] = Stats(science = amountConverted, gold = -amountConverted)
        }
        for (entry in newFinalStatList.values) {
            entry.science *= statPercentBonusesSum.science.toPercent()
        }


        //
        /* Okay, food calculation is complicated.
        First we see how much food we generate. Then we apply production bonuses to it.
        Up till here, business as usual.
        Then, we deduct food eaten (from the total produced).
        Now we have the excess food, which has its own things. If we're unhappy, cut it by 1/4.
        Some policies have bonuses for excess food only, not general food production.
         */

        updateFoodEaten()
        newFinalStatList["Population"]!!.food -= foodEaten

        var totalFood = newFinalStatList.values.map { it.food }.sum()

        if (isUnhappy && totalFood > 0) { // Reduce excess food to 1/4 per the same
            val foodReducedByUnhappiness = Stats(food = totalFood * (-3 / 4f))
            baseStatList = LinkedHashMap(baseStatList).apply { put("Unhappiness", foodReducedByUnhappiness) } // concurrency-safe addition
            newFinalStatList["Unhappiness"] = foodReducedByUnhappiness
        }

        totalFood = newFinalStatList.values.map { it.food }.sum() // recalculate because of previous change

        // Since growth bonuses are special, (applied afterwards) they will be displayed separately in the user interface as well.
        if (totalFood > 0 && !isUnhappy) { // Percentage Growth bonus revoked when unhappy per https://forums.civfanatics.com/resources/complete-guide-to-happiness-vanilla.25584/
            val foodFromGrowthBonuses = getGrowthBonusFromPoliciesAndWonders() * totalFood
            newFinalStatList["Policies"]!!.food += foodFromGrowthBonuses // Why Policies? Wonders can also provide this?
            totalFood = newFinalStatList.values.map { it.food }.sum() // recalculate again
        }

        val buildingsMaintenance = getBuildingMaintenanceCosts(citySpecificUniques) // this is AFTER the bonus calculation!
        newFinalStatList["Maintenance"] = Stats(gold = -buildingsMaintenance.toInt().toFloat())

        if (totalFood > 0 && constructionMatchesFilter(currentConstruction, "Excess Food converted to Production when under construction")) {
            newFinalStatList["Excess food to production"] = Stats(production = totalFood, food = -totalFood)
        }

        if (cityInfo.isInResistance())
            newFinalStatList.clear()  // NOPE

        if (newFinalStatList.values.map { it.production }.sum() < 1)  // Minimum production for things to progress
            newFinalStatList["Production"] = Stats(production = 1f)
        finalStatList = newFinalStatList
    }

    private fun updateFoodEaten() {
        foodEaten = cityInfo.population.population.toFloat() * 2
        var foodEatenBySpecialists = 2f * cityInfo.population.getNumberOfSpecialists()

        // Deprecated since 3.16.11
            for (unique in cityInfo.civInfo.getMatchingUniques("-[]% food consumption by specialists"))
                foodEatenBySpecialists *= 1f - unique.params[0].toFloat() / 100f
        //

        for (unique in cityInfo.getMatchingUniques("[]% food consumption by specialists []"))
            if (cityInfo.matchesFilter(unique.params[1]))
                foodEatenBySpecialists *= unique.params[0].toPercent()

        foodEaten -= 2f * cityInfo.population.getNumberOfSpecialists() - foodEatenBySpecialists
    }

    //endregion
}
