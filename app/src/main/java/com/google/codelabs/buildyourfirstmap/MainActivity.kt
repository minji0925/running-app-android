package com.google.codelabs.buildyourfirstmap
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
import android.util.Log
import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONObject
import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray

val api_key = BuildConfig.GOOGLE_MAPS_API_KEY
val graphhopper_api = BuildConfig.GRAPHHOPPER_API_KEY

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

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mMap: GoogleMap
    private lateinit var startingPoint: EditText
    private lateinit var destination: EditText
    private lateinit var button: Button
    private val routeFetcher = RouteFetcher(graphhopper_api)
    val combinedResults = mutableListOf<JSONObject>()

    private var searchTypes = listOf("park", "trail", "hiking", "tourist_attraction")
    private var keywordTypes = listOf("running path", "scenic view")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startingPoint = findViewById(R.id.editTextStart)
        destination = findViewById(R.id.editTextDestination)
        button = findViewById(R.id.button)

        // Initialize the map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Initialize Places API
        Places.initialize(applicationContext, api_key)
        val placesClient: PlacesClient = Places.createClient(this)

        button.setOnClickListener {
            val start = startingPoint.text.toString().trim()
            val dest = destination.text.toString().trim()

            if (start.isNotEmpty() && dest.isNotEmpty()) {
                // Use Geocoder API to fetch LatLng
                val geocoder = Geocoder(this)
                val addressList_start = geocoder.getFromLocationName(start, 1)
                val addressList_dest = geocoder.getFromLocationName(dest, 1)
                //change maxresults if i want more than 1 results
                if (addressList_start != null && addressList_start.isNotEmpty() && addressList_dest != null && addressList_dest.isNotEmpty()) {
                    val address_start = addressList_start[0]
                    val start_latLng = LatLng(address_start.latitude, address_start.longitude)
                    val address_dest = addressList_dest[0]
                    val dest_latLng = LatLng(address_dest.latitude, address_dest.longitude)

                    // Add marker and move camera
                    mMap.addMarker(MarkerOptions().position(start_latLng).title(start))
                    mMap.addMarker(MarkerOptions().position(dest_latLng).title(dest))
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start_latLng, 12f))

                    //fetchNearbyPlaces(latLng)
                    //fetchRoutes(start_latLng, dest_latLng)
                    fetchNearbyPlaces(start_latLng)
                    useNearbyPlacesAsWaypoints(start_latLng, dest_latLng)
                    useNearbyPlacesAsWaypoints(start_latLng, dest_latLng)
                    useNearbyPlacesAsWaypoints(start_latLng, dest_latLng)
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Enter a valid location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
    }

    private fun fetchNearbyPlaces(location: LatLng) {
        val radius = 2000 // Search radius in meters
        val client = OkHttpClient()

        // Helper function to handle API responses
        fun handleResponse(responseData: String?) {
            if (responseData == null) return
            try {
                val json = JSONObject(responseData)
                val results = json.optJSONArray("results") ?: JSONArray()
                synchronized(combinedResults) {
                    // Add up to 10 results or fewer if there are not enough
                    for (i in 0 until minOf(results.length(), 10)) {
                        combinedResults.add(results.getJSONObject(i))
                    }
                }
                runOnUiThread {
                    //displayResultsOnMap(combinedResults.take(50)) // Display up to 50 markers
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("FetchNearbyPlaces", "Error parsing response: ${e.message}")
            }
        }

        // Fetch places for each type in searchTypes
        for (type in searchTypes) {
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=${location.latitude},${location.longitude}" +
                    "&radius=$radius&type=$type&key=$api_key"

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    Log.e("FetchNearbyPlaces", "Request failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.body?.string()?.let(::handleResponse)
                    Log.e("FetchNearbyPlaces", "Request failed: $combinedResults")
                }
            })
        }

        // Fetch places for each keyword in keywordTypes
        for (keyword in keywordTypes) {
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=${location.latitude},${location.longitude}" +
                    "&radius=$radius&keyword=$keyword&key=$api_key"

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    Log.e("FetchNearbyPlaces", "Request failed: ${e.message}")
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
                    val json = JSONObject(responseData)
                    val paths = json.getJSONArray("paths")
                    if (paths.length() > 0) {
                        val pointsEncoded = paths.getJSONObject(0).getString("points")
                        val polyline = decodePolyline(pointsEncoded)
                        runOnUiThread {
                            displayRouteOnMap(polyline)
                        }
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
}