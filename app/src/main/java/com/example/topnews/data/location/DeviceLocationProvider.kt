package com.example.topnews.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.topnews.domain.model.DeviceLocation
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class DeviceLocationProvider(
    private val context: Context
) {
    suspend fun getDeviceLocation(): DeviceLocation? {
        if (!hasLocationPermission()) return null

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val lastKnownLocation = getLastKnownLocation(locationManager)
        val location = lastKnownLocation ?: requestSingleLocation(locationManager)
        return location?.toDeviceLocation()
    }

    private fun hasLocationPermission(): Boolean {
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarseGranted || fineGranted
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider -> locationManager.isProviderEnabled(provider) }
            .mapNotNull { provider -> locationManager.getLastKnownLocation(provider) }
            .maxByOrNull { location -> location.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation(locationManager: LocationManager): Location? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { provider -> locationManager.isProviderEnabled(provider) }
            ?: return null

        return suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            continuation.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }
        }
    }

    private suspend fun Location.toDeviceLocation(): DeviceLocation {
        val city = withContext(Dispatchers.IO) {
            runCatching {
                Geocoder(context, Locale.getDefault())
                    .getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()
                    ?.locality
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        } ?: "当前位置"

        return DeviceLocation(
            latitude = latitude,
            longitude = longitude,
            city = city
        )
    }
}
