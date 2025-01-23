package com.google.codelabs.buildyourfirstmap
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import android.location.Geocoder
import android.net.http.UrlRequest
import android.util.Log

import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONObject
import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList
import android.os.Build


import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.AutocompleteActivity
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import kotlinx.coroutines.runBlocking
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.android.libraries.places.widget.model.PlaceSelectionListener
import com.google.android.libraries.places.widget.model.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch


val api_key = BuildConfig.GOOGLE_MAPS_API_KEY
val graphhopper_api = BuildConfig.GRAPHHOPPER_API_KEY
val openai_api = BuildConfig.OPENAI_API_KEY
val openAI = OpenAI(token = openai_api, logging = LoggingConfig(LogLevel.Info))

class RouteFetcher(private val apiKey: String) {
    val client = OkHttpClient()
    val url = "https://graphhopper.com/api/1/route?key=$apiKey"

    fun fetchRoutes(start: LatLng, end: LatLng, callback: (JSONObject?) -> Unit) {
        val jsonBody = """
            {
                "points": [
                    [${start.longitude}, ${start.latitude}],
                    [${end.longitude}, ${end.latitude}]
                ],
                "profile": "foot",
                "locale": "en",
                "instructions": true,
                "calc_points": true,
                "points_encoded": false
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), jsonBody))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                callback(if (responseData != null) JSONObject(responseData) else null)
            }
        })
    }

    private fun geocodeAddress(address: String, callback: (LatLng?) -> Unit) {
        val client = OkHttpClient()
        val url = "https://graphhopper.com/api/1/geocode?q=${address}&locale=en&key=YOUR_GRAPHHOPPER_API_KEY"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseData ->
                    val json = JSONObject(responseData)
                    val hits = json.getJSONArray("hits")
                    if (hits.length() > 0) {
                        val firstHit = hits.getJSONObject(0)
                        val lat = firstHit.getDouble("point.lat")
                        val lng = firstHit.getDouble("point.lng")
                        callback(LatLng(lat, lng))
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }
}

class WeatherService {
    private val accuWeatherApiKey = "your_accuweather_api_key"
    private val client = OkHttpClient()

    suspend fun getWeatherData(latitude: Double, longitude: Double): WeatherData? {
        // First get location key
        val locationKey = getLocationKey(latitude, longitude) ?: return null
        
        // Then get weather data
        return getCurrentConditions(locationKey)
    }

    private suspend fun getLocationKey(latitude: Double, longitude: Double): String? {
        val url = "http://dataservice.accuweather.com/locations/v1/cities/geoposition/search" +
                "?apikey=$accuWeatherApiKey&q=$latitude,$longitude"

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val jsonData = JSONObject(response.body?.string() ?: return@withContext null)
                jsonData.getString("Key")
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun getCurrentConditions(locationKey: String): WeatherData? {
        val url = "http://dataservice.accuweather.com/currentconditions/v1/$locationKey" +
                "?apikey=$accuWeatherApiKey"

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val jsonArray = JSONArray(response.body?.string())
                val weatherJson = jsonArray.getJSONObject(0)
                
                WeatherData(
                    temperature = weatherJson
                        .getJSONObject("Temperature")
                        .getJSONObject("Metric")
                        .getDouble("Value"),
                    weatherText = weatherJson.getString("WeatherText"),
                    isDayTime = weatherJson.getBoolean("IsDayTime")
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}

data class WeatherData(
    val temperature: Double,
    val weatherText: String,
    val isDayTime: Boolean
)

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mMap: GoogleMap
    private lateinit var startingPoint: EditText
    private lateinit var destination: EditText
    private lateinit var distancePreference: EditText
    private lateinit var button: Button
    private val routeFetcher = RouteFetcher(graphhopper_api)
    val combinedResults = CopyOnWriteArrayList<JSONObject>() // Thread-safe list
    private val allRoutes = JSONArray() // Store all generated routes

    private var searchTypes = listOf("park", "trail", "hiking", "tourist_attraction")
    private var keywordTypes = listOf("running path", "scenic view")

    private val weatherService = WeatherService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startingPoint = findViewById(R.id.editTextStart)
        destination = findViewById(R.id.editTextDestination)
        distancePreference = findViewById(R.id.editTextDistance)
        button = findViewById(R.id.button)

        // Initialize the map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Initialize Places API
        Places.initialize(applicationContext, api_key)
        val placesClient: PlacesClient = Places.createClient(this)

        // Setup Places Autocomplete for starting point
        setupPlacesAutocomplete(startingPoint)
        // Setup Places Autocomplete for destination
        setupPlacesAutocomplete(destination)

        button.setOnClickListener {
            val start = startingPoint.text.toString().trim()
            val dest = destination.text.toString().trim()

            if (start.isNotEmpty() && dest.isNotEmpty()) {
                val geocoder = Geocoder(this)

                // Use the asynchronous Geocoder API introduced in Android 33
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocationName(start, 1) { addressListStart ->
                        geocoder.getFromLocationName(dest, 1) { addressListDest ->
                            if (!addressListStart.isNullOrEmpty() && !addressListDest.isNullOrEmpty()) {
                                val addressStart = addressListStart[0]
                                val startLatLng = LatLng(addressStart.latitude, addressStart.longitude)

                                val addressDest = addressListDest[0]
                                val destLatLng = LatLng(addressDest.latitude, addressDest.longitude)

                                // Wrap map operations in runOnUiThread
                                runOnUiThread {
                                    // Add marker and move camera
                                    mMap.addMarker(MarkerOptions().position(startLatLng).title(start))
                                    mMap.addMarker(MarkerOptions().position(destLatLng).title(dest))
                                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, 12f))
                                }

                                // Continue with route fetching
                                fetchNearbyPlaces(startLatLng) {
                                    // Generate multiple routes
                                    repeat(5) { // Generate 5 different routes
                                        useNearbyPlacesAsWaypoints(startLatLng, destLatLng)
                                    }

                                    // Save routes to a JSON file
                                    val routesJson = JSONObject()
                                    routesJson.put("routes", allRoutes)

                                    val file = File(applicationContext.filesDir, "routes.json")
                                    file.writeText(routesJson.toString())

                                    // Use OpenAI to analyze routes
                                    analyzeRoutesWithOpenAI(file)
                                }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else {
                    // For older Android versions, use the synchronous API
                    try {
                        val addressListStart = geocoder.getFromLocationName(start, 1)
                        val addressListDest = geocoder.getFromLocationName(dest, 1)

                        if (!addressListStart.isNullOrEmpty() && !addressListDest.isNullOrEmpty()) {
                            val addressStart = addressListStart[0]
                            val startLatLng = LatLng(addressStart.latitude, addressStart.longitude)

                            val addressDest = addressListDest[0]
                            val destLatLng = LatLng(addressDest.latitude, addressDest.longitude)

                            // Wrap map operations in runOnUiThread
                            runOnUiThread {
                                // Add marker and move camera
                                mMap.addMarker(MarkerOptions().position(startLatLng).title(start))
                                mMap.addMarker(MarkerOptions().position(destLatLng).title(dest))
                                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, 12f))
                            }

                            // Fetch routes and analyze them
                            fetchNearbyPlaces(startLatLng) {
                                // Generate multiple routes
                                repeat(5) { // Generate 5 different routes
                                    useNearbyPlacesAsWaypoints(startLatLng, destLatLng)
                                }

                                // Save routes to a JSON file
                                val routesJson = JSONObject()
                                routesJson.put("routes", allRoutes)

                                val file = File(applicationContext.filesDir, "routes.json")
                                file.writeText(routesJson.toString())

                                // Use OpenAI to analyze routes
                                analyzeRoutesWithOpenAI(file)
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: IOException) {
                        runOnUiThread {
                            Toast.makeText(this, "Failed to fetch location: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Enter a valid location", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupPlacesAutocomplete(editText: EditText) {
        editText.setOnClickListener {
            // Initialize Places Autocomplete Fragment
            val autocompleteFragment = AutocompleteSupportFragment.newInstance()
            
            // Specify the types of place data to return
            autocompleteFragment.setPlaceFields(listOf(
                Place.Field.ID,
                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.LAT_LNG
            ))

            // Set up the callback
            autocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
                override fun onPlaceSelected(place: Place) {
                    editText.setText(place.address)
                    supportFragmentManager.beginTransaction()
                        .remove(autocompleteFragment)
                        .commit()
                }

                override fun onError(status: UrlRequest.Status) {
                    Toast.makeText(applicationContext, "Error: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
                    supportFragmentManager.beginTransaction()
                        .remove(autocompleteFragment)
                        .commit()
                }
            })

            // Show the fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.autocomplete_fragment_container, autocompleteFragment)
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
    }

    private fun fetchNearbyPlaces(location: LatLng, onComplete: () -> Unit) {
        val radius = 2000 // Search radius in meters
        val client = OkHttpClient()

        var pendingRequests = 0

        fun handleResponse(responseData: String?) {
            if (responseData == null) return
            try {
                val json = JSONObject(responseData)
                val results = json.optJSONArray("results") ?: JSONArray()
                synchronized(combinedResults) {
                    for (i in 0 until minOf(results.length(), 10)) {
                        combinedResults.add(results.getJSONObject(i))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                synchronized(this@MainActivity) {
                    pendingRequests--
                    if (pendingRequests == 0) onComplete()
                }
            }
        }

        val allSearchTypes = searchTypes + keywordTypes
        pendingRequests = allSearchTypes.size

        for (typeOrKeyword in allSearchTypes) {
            val param = if (typeOrKeyword in searchTypes) "type" else "keyword"
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=${location.latitude},${location.longitude}" +
                    "&radius=$radius&$param=$typeOrKeyword&key=$api_key"

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    synchronized(this@MainActivity) {
                        pendingRequests--
                        if (pendingRequests == 0) onComplete()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.body?.string()?.let(::handleResponse)
                }
            })
        }
    }

    private fun fetchRoutes(start: LatLng, end: LatLng) {
        val client = OkHttpClient()
        val url = "https://graphhopper.com/api/1/route" +
                "?point=${start.latitude},${start.longitude}" +
                "&point=${end.latitude},${end.longitude}" +
                "&profile=foot" +
                "&key=$graphhopper_api"

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseData ->
                    val json = JSONObject(responseData)
                    val paths = json.getJSONArray("paths")
                    if (paths.length() > 0) {
                        val pointsEncoded = paths.getJSONObject(0).getString("points")
                        print(paths.getJSONObject(0).getString("points"))
                        val polyline = decodePolyline(pointsEncoded)
                        runOnUiThread {
                            displayRouteOnMap(polyline)
                        }
                    }
                }
            }
        })
    }

    private fun useNearbyPlacesAsWaypoints(start: LatLng, end: LatLng) {
        if (combinedResults.size < 2) {
            Log.e("Waypoints", "Not enough nearby places to select waypoints")
            return
        }

        // Randomly select two places
        val shuffledResults = combinedResults.shuffled()
        val waypoint1 = shuffledResults[0]
        val waypoint2 = shuffledResults[1]

        // Extract LatLng from the waypoints
        val waypoint1LatLng = LatLng(
            waypoint1.getJSONObject("geometry").getJSONObject("location").getDouble("lat"),
            waypoint1.getJSONObject("geometry").getJSONObject("location").getDouble("lng")
        )
        val waypoint2LatLng = LatLng(
            waypoint2.getJSONObject("geometry").getJSONObject("location").getDouble("lat"),
            waypoint2.getJSONObject("geometry").getJSONObject("location").getDouble("lng")
        )

        // Log selected waypoints for debugging
        Log.d("Waypoints", "Selected waypoints: $waypoint1LatLng, $waypoint2LatLng")

        // Fetch routes using waypoints
        fetchRoutesWithWaypoints(start, end, listOf(waypoint1LatLng, waypoint2LatLng))
    }

    private fun fetchRoutesWithWaypoints(start: LatLng, end: LatLng, waypoints: List<LatLng>) {
        val client = OkHttpClient()

        // Build the route URL with waypoints
        val waypointsQuery = waypoints.joinToString("&") { "point=${it.latitude},${it.longitude}" }
        val url = "https://graphhopper.com/api/1/route" +
                "?point=${start.latitude},${start.longitude}" +
                "&$waypointsQuery" +
                "&point=${end.latitude},${end.longitude}" +
                "&profile=foot" +
                "&key=$graphhopper_api"

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseData ->
                    try {
                        val json = JSONObject(responseData)
                        val paths = json.getJSONArray("paths")
                        val path = paths.getJSONObject(0)

                        // Create a route object with all relevant information
                        val routeObject = JSONObject()
                        routeObject.put("path", path)
                        routeObject.put("waypoints", JSONArray().apply {
                            put(JSONObject().put("lat", waypoints[0].latitude).put("lng", waypoints[0].longitude))
                            put(JSONObject().put("lat", waypoints[1].latitude).put("lng", waypoints[1].longitude))
                        })
                        
                        // Add to allRoutes
                        synchronized(allRoutes) {
                            allRoutes.put(routeObject)
                        }

                        // Extract route information
                        val distance = path.getDouble("distance") // in meters
                        val time = path.getLong("time") // in milliseconds
                        val pointsEncoded = path.getString("points") // encoded polyline string

                        // Extract turn-by-turn instructions
                        val instructions = path.optJSONArray("instructions")
                        instructions?.let {
                            for (i in 0 until it.length()) {
                                val instruction = it.getJSONObject(i)
                                val text = instruction.getString("text")
                                val streetName = instruction.optString("street_name", "N/A")
                                val instructionDistance = instruction.getDouble("distance")
                                val instructionTime = instruction.getLong("time")

                                // Log instruction details
                                Log.d(
                                    "RouteInstructions",
                                    "Instruction: $text, Street: $streetName, " +
                                            "Distance: $instructionDistance, Time: $instructionTime"
                                )
                            }
                        }

                        // Log high-level route data
                        Log.d(
                            "RouteSummary",
                            "Distance: $distance meters, Time: $time ms, Points: $pointsEncoded"
                        )

                        // Decode the polyline and display the route on the map
                        val polyline = decodePolyline(pointsEncoded)
                        runOnUiThread {
                            displayRouteOnMap(polyline)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e("RouteResponse", "Error parsing JSON: ${e.message}")
                    }
                }
            }
        })
    }

    private fun displayResultsOnMap(results: List<JSONObject>) {
        for (result in results) {
            val location = result.getJSONObject("geometry").getJSONObject("location")
            val lat = location.getDouble("lat")
            val lng = location.getDouble("lng")
            val name = result.getString("name")

            val latLng = LatLng(lat, lng)
            mMap.addMarker(MarkerOptions().position(latLng).title(name))
        }
    }
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dLng

            val latLng = LatLng(lat / 1E5, lng / 1E5)
            poly.add(latLng)
        }
        return poly
    }

    private fun displayRouteOnMap(polyline: List<LatLng>) {
        mMap.addPolyline(PolylineOptions().addAll(polyline).color(Color.BLUE).width(5f))
    }

    private fun analyzeRoutesWithOpenAI(routesFile: File) = runBlocking {
        // Read the file in chunks or limit the data size
        val routesData = try {
            val jsonObject = JSONObject(routesFile.readText())
            val routes = jsonObject.getJSONArray("routes")
            // Limit the number of routes or their size
            val limitedRoutes = JSONArray()
            for (i in 0 until minOf(routes.length(), 3)) { // Limit to 3 routes
                val route = routes.getJSONObject(i)
                // Create a simplified version of the route
                val simplifiedRoute = JSONObject()
                simplifiedRoute.put("distance", route.getJSONObject("path").optDouble("distance"))
                simplifiedRoute.put("time", route.getJSONObject("path").optDouble("time"))
                // Only include essential waypoints information
                simplifiedRoute.put("waypoints", route.optJSONArray("waypoints"))
                limitedRoutes.put(simplifiedRoute)
            }
            JSONObject().put("routes", limitedRoutes).toString()
        } catch (e: Exception) {
            Log.e("OpenAI Analysis", "Error processing routes file: ${e.message}")
            return@runBlocking
        }
        
        val prompt = """
            Analyze these walking routes and select the best one based on the following criteria:
            1. Total distance (shorter is better but not crucial)
            2. Interesting waypoints and attractions along the way
            3. Safety and walkability of the route
            4. Scenic value
            
            Routes data: $routesData
            
            Please provide the index of the best route (0-${minOf(allRoutes.length() - 1, 2)}) and a brief explanation why.
        """.trimIndent()

        try {
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId("gpt-3.5-turbo"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                )
            )

            val completion: ChatCompletion = openAI.chatCompletion(chatCompletionRequest)
            val response = completion.choices.first().message.content ?: ""
            
            // Process the response in smaller chunks
            runOnUiThread {
                try {
                    val bestRouteIndex = response.let {
                        val matcher = """\d+""".toRegex().find(it)
                        matcher?.value?.toIntOrNull() ?: 0
                    }
                    
                    if (bestRouteIndex < allRoutes.length()) {
                        val bestRoute = allRoutes.getJSONObject(bestRouteIndex)
                        displayBestRoute(bestRoute)
                        Toast.makeText(this@MainActivity, "Best route selected!", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e("OpenAI Analysis", "Invalid route index: $bestRouteIndex")
                        displayBestRoute(allRoutes.getJSONObject(0))
                    }
                } catch (e: Exception) {
                    Log.e("OpenAI Analysis", "Error displaying route: ${e.message}")
                    Toast.makeText(this@MainActivity, "Error displaying route", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Log the AI's explanation in chunks
            val chunkSize = 1000
            response.chunked(chunkSize).forEach { chunk ->
                Log.d("OpenAI Analysis", chunk)
            }
            
        } catch (e: Exception) {
            Log.e("OpenAI Error", "Failed to analyze routes: ${e.message}")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Failed to analyze routes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayBestRoute(routeJson: JSONObject) {
        // Implementation to display the selected route on the map
        val points = routeJson.getJSONObject("path").getString("points")
        val polyline = decodePolyline(points)
        runOnUiThread {
            displayRouteOnMap(polyline)
        }
    }

    private suspend fun processRouteWithWeather(startLatLng: LatLng, destLatLng: LatLng) {
        // Get weather for both locations
        val startWeather = weatherService.getWeatherData(
            startLatLng.latitude,
            startLatLng.longitude
        )
        val destWeather = weatherService.getWeatherData(
            destLatLng.latitude,
            destLatLng.longitude
        )

        // Create weather context for AI model
        val weatherContext = buildString {
            append("Weather conditions:\n")
            append("Starting point: ${startWeather?.weatherText ?: "Unknown"}, ")
            append("Temperature: ${startWeather?.temperature ?: "Unknown"}°C\n")
            append("Destination: ${destWeather?.weatherText ?: "Unknown"}, ")
            append("Temperature: ${destWeather?.temperature ?: "Unknown"}°C")
        }

        // Add weather context to your AI model input
        val aiInput = """
            Route from ${startLatLng.latitude},${startLatLng.longitude} 
            to ${destLatLng.latitude},${destLatLng.longitude}
            $weatherContext
            Please suggest route modifications based on weather conditions.
        """.trimIndent()

        // Process with your AI model...
    }
}