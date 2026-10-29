package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.model.GeofenceZoneEntity
import com.example.location.LocationState

/**
 * Delegates directly to the Google Maps SDK MapView implementation in TileMapCanvas
 * so all map surfaces across the app use hardware-accelerated Google Maps with deep zooming.
 */
@Composable
fun InteractiveMapCanvas(
  locationState: LocationState,
  geofences: List<GeofenceZoneEntity>,
  childName: String? = null,
  childLat: Double? = null,
  childLng: Double? = null,
  onCenterClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  TileMapCanvas(
    locationState = locationState,
    geofences = geofences,
    childName = childName,
    childLat = childLat,
    childLng = childLng,
    onCenterClick = onCenterClick,
    modifier = modifier
  )
}
