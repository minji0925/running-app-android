import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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