// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.codelabs.buildyourfirstmap
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
import org.json.JSONObject
import java.io.IOException
import okhttp3.*

val api_key = BuildConfig.GOOGLE_MAPS_API_KEY


class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mMap: GoogleMap
    private lateinit var editText: EditText
    private lateinit var button: Button

    private var searchTypes = listOf("park", "trail", "hiking", "tourist_attraction")
    private var keywordTypes = listOf("running path", "scenic view")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editText = findViewById(R.id.editTextText)
        button = findViewById(R.id.button)

        // Initialize the map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Initialize Places API
        Places.initialize(applicationContext, api_key)
        val placesClient: PlacesClient = Places.createClient(this)

        /*button.setOnClickListener {
            var location = editText.text.toString().trim()
            location = "ChIJgf4OJaelfDURmDvA_sHyPUM"
            if (location.isNotEmpty()) {
                val latLng = LatLng(37.7749, -122.4194) // Replace with geocoded LatLng
                mMap.addMarker(MarkerOptions().position(latLng).title(location))
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f))
            } else {
                Toast.makeText(this, "Enter a valid location", Toast.LENGTH_SHORT).show()
            }
        }*/

        button.setOnClickListener {
            val locationName = editText.text.toString().trim()

            if (locationName.isNotEmpty()) {
                // Use Geocoder API to fetch LatLng
                val geocoder = Geocoder(this)
                val addressList = geocoder.getFromLocationName(locationName, 1)
                if (addressList != null && addressList.isNotEmpty()) {
                    val address = addressList[0]
                    val latLng = LatLng(address.latitude, address.longitude)

                    // Add marker and move camera
                    mMap.addMarker(MarkerOptions().position(latLng).title(locationName))
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f))

                    fetchNearbyPlaces(latLng)
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
        val combinedResults = mutableListOf<JSONObject>()

        val client = OkHttpClient()

        // Fetch places based on searchTypes
        for (type in searchTypes) {
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=${location.latitude},${location.longitude}" +
                    "&radius=$radius&type=$type&key=$api_key"

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.body?.string()?.let { responseData ->
                        val json = JSONObject(responseData)
                        val results = json.getJSONArray("results")
                        for (i in 0 until results.length()) {
                            combinedResults.add(results.getJSONObject(i))
                        }
                        runOnUiThread {
                            displayResultsOnMap(combinedResults)
                        }
                    }
                }
            })
        }

        // Fetch places based on keywordTypes
        for (keyword in keywordTypes) {
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=${location.latitude},${location.longitude}" +
                    "&radius=$radius&keyword=$keyword&key=$api_key"

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.body?.string()?.let { responseData ->
                        val json = JSONObject(responseData)
                        val results = json.getJSONArray("results")
                        for (i in 0 until results.length()) {
                            combinedResults.add(results.getJSONObject(i))
                        }
                        runOnUiThread {
                            displayResultsOnMap(combinedResults)
                        }
                    }
                }
            })
        }
}
