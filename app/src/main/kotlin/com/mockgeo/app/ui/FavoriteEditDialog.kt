package com.mockgeo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockgeo.core.model.Favorite
import com.mockgeo.core.model.LatLng

@Composable
internal fun FavoriteEditDialog(favorite: Favorite, onDismiss: () -> Unit, onSave: (String, LatLng, Double?) -> Unit) {
    var name by rememberSaveable(favorite.id) { mutableStateOf(favorite.name) }
    var lat by rememberSaveable(favorite.id) { mutableStateOf(favorite.lat.toString()) }
    var lng by rememberSaveable(favorite.id) { mutableStateOf(favorite.lng.toString()) }
    var altitude by rememberSaveable(favorite.id) { mutableStateOf(favorite.altitude?.toString().orEmpty()) }
    val latitude = lat.trim().toDoubleOrNull()
    val longitude = lng.trim().toDoubleOrNull()
    val altitudeM = altitude.trim().toDoubleOrNull()
    val validLatitude = latitude != null && latitude.isFinite() && latitude in -90.0..90.0
    val validLongitude = longitude != null && longitude.isFinite() && longitude in -180.0..180.0
    val validAltitude = altitude.isBlank() || (altitudeM != null && altitudeM.isFinite() && altitudeM in -500.0..9000.0)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑收藏") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("位置名称") }, singleLine = true)
                OutlinedTextField(lat, { lat = it }, Modifier.fillMaxWidth(), label = { Text("纬度 · WGS 84") }, singleLine = true,
                    isError = !validLatitude, supportingText = { Text("−90 ～ 90") })
                OutlinedTextField(lng, { lng = it }, Modifier.fillMaxWidth(), label = { Text("经度 · WGS 84") }, singleLine = true,
                    isError = !validLongitude, supportingText = { Text("−180 ～ 180") })
                OutlinedTextField(altitude, { altitude = it }, Modifier.fillMaxWidth(), label = { Text("海拔（可选，米）") }, singleLine = true,
                    isError = !validAltitude, supportingText = { Text("留空使用默认海拔，范围 −500 ～ 9000") })
            }
        },
        confirmButton = { Button(onClick = { onSave(name.trim(), LatLng(latitude!!, longitude!!), altitudeM) }, enabled = name.isNotBlank() && validLatitude && validLongitude && validAltitude) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
