package com.rfsentinel.app.ui

/**
 * Map details the OpenFreeMap night style doesn't draw but the light map does (one-way arrows, river and
 * lake names, path names, highway shields, airports): the light map's own layer definitions (same
 * tiles, icons and zoom levels), with night colours. Shops and landmarks are added in code.
 */
internal object NightLabels {

    private const val NAME = """["case",["has","name:nonlatin"],["concat",["get","name:latin"]," ",["get","name:nonlatin"]],["coalesce",["get","name_en"],["get","name"]]]"""
    private const val NAME_LINES = """["case",["has","name:nonlatin"],["concat",["get","name:latin"],"\n",["get","name:nonlatin"]],["coalesce",["get","name_en"],["get","name"]]]"""
    private const val PAINT = """"paint":{"text-color":"#D3DCEC","text-halo-color":"#1C2436","text-halo-width":1.6}"""
    private const val LINE = """"symbol-placement":"line","text-rotation-alignment":"map""""
    private const val LINES = """["match",["geometry-type"],["LineString","MultiLineString"],true,false]"""
    private const val SHIELD_LAYOUT = """"icon-rotation-alignment":"viewport","icon-size":1,"symbol-spacing":200,"text-field":["to-string",["get","ref"]],"text-font":["Noto Sans Regular"],"text-rotation-alignment":"viewport","text-size":10"""

    val layers: List<String> = listOf(
        """{"id":"night-oneway","type":"symbol","source":"openmaptiles","source-layer":"transportation","minzoom":16,"filter":["==",["get","oneway"],1],"layout":{"icon-image":"arrow","symbol-placement":"line"}}""",
        """{"id":"night-oneway-back","type":"symbol","source":"openmaptiles","source-layer":"transportation","minzoom":16,"filter":["==",["get","oneway"],-1],"layout":{"icon-image":"arrow","icon-rotate":180,"symbol-placement":"line"}}""",
        """{"id":"night-waterway-label","type":"symbol","source":"openmaptiles","source-layer":"waterway","minzoom":10,"filter":$LINES,"layout":{"symbol-placement":"line","symbol-spacing":350,"text-field":$NAME,"text-font":["Noto Sans Italic"],"text-letter-spacing":0.2,"text-max-width":5,"text-size":14},$PAINT}""",
        """{"id":"night-water-point-label","type":"symbol","source":"openmaptiles","source-layer":"water_name","filter":["match",["geometry-type"],["MultiPoint","Point"],true,false],"layout":{"text-field":$NAME_LINES,"text-font":["Noto Sans Italic"],"text-letter-spacing":0.2,"text-max-width":5,"text-size":["interpolate",["linear"],["zoom"],0,10,8,14]},$PAINT}""",
        """{"id":"night-path-name","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","minzoom":15.5,"filter":["==",["get","class"],"path"],"layout":{$LINE,"text-field":$NAME,"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],13,12,14,13]},$PAINT}""",
        """{"id":"night-airport","type":"symbol","source":"openmaptiles","source-layer":"aerodrome_label","minzoom":10,"filter":["all",["has","iata"]],"layout":{"icon-image":"airport_11","icon-size":1,"text-anchor":"top","text-field":$NAME_LINES,"text-font":["Noto Sans Regular"],"text-max-width":9,"text-offset":[0,0.6],"text-optional":true,"text-padding":2,"text-size":12},$PAINT}""",
        // Shields keep the light map's look: dark text on the light badge (not recoloured at night).
        """{"id":"night-shield-other","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","minzoom":8,"filter":["all",["<=",["get","ref_length"],6],$LINES,["match",["get","network"],["us-highway","us-interstate","us-state"],false,true]],"layout":{"icon-image":["concat","road_",["get","ref_length"]],"symbol-placement":["step",["zoom"],"point",11,"line"],$SHIELD_LAYOUT}}""",
        """{"id":"night-shield-interstate","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","minzoom":7,"filter":["all",["<=",["get","ref_length"],6],$LINES,["match",["get","network"],["us-interstate"],true,false]],"layout":{"icon-image":["concat",["get","network"],"_",["get","ref_length"]],"symbol-placement":["step",["zoom"],"point",7,"line",8,"line"],$SHIELD_LAYOUT}}""",
        """{"id":"night-shield-us","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","minzoom":9,"filter":["all",["<=",["get","ref_length"],6],$LINES,["match",["get","network"],["us-highway","us-state"],true,false]],"layout":{"icon-image":["concat",["get","network"],"_",["get","ref_length"]],"symbol-placement":["step",["zoom"],"point",11,"line"],$SHIELD_LAYOUT}}"""
    )
}
