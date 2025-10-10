package com.example.myapplication.ui.components

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles

@Composable
fun KakaoMapViewCompose(
    lat: Double,
    lon: Double,
    zoom: Int = 15,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var kakaoMapInstance by remember { mutableStateOf<KakaoMap?>(null) }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                start(
                    object : MapLifeCycleCallback() {
                        override fun onMapDestroy() { Toast.makeText(context, "지도 종료", Toast.LENGTH_SHORT).show() }
                        override fun onMapError(error: Exception) { Toast.makeText(context, "지도 오류: ${error.message}", Toast.LENGTH_LONG).show() }
                    },
                    object : KakaoMapReadyCallback() {
                        override fun onMapReady(kakaoMap: KakaoMap) {
                            kakaoMapInstance = kakaoMap
                            Toast.makeText(context, "지도 로드 완료!", Toast.LENGTH_SHORT).show()

                            val position = LatLng.from(lat, lon)
                            kakaoMap.labelManager?.layer?.let { layer ->
                                val labelStyle = LabelStyle.from(android.R.drawable.ic_menu_mylocation)
                                val labelStyles = LabelStyles.from(labelStyle)
                                val options = LabelOptions.from(position).setStyles(labelStyles)
                                layer.addLabel(options)
                            }
                        }

                        override fun getPosition(): LatLng = LatLng.from(lat, lon)
                        override fun getZoomLevel(): Int = zoom
                    }
                )
            }
        },
        modifier = modifier
    )

    LaunchedEffect(lat, lon) {
        kakaoMapInstance?.let { map ->
            val pos = LatLng.from(lat, lon)
            map.moveCamera(CameraUpdateFactory.newCenterPosition(pos, zoom))
            map.labelManager?.layer?.let { layer ->
                layer.removeAll()
                val labelStyle = LabelStyle.from(android.R.drawable.ic_menu_mylocation)
                val labelStyles = LabelStyles.from(labelStyle)
                val options = LabelOptions.from(pos).setStyles(labelStyles)
                layer.addLabel(options)
            }
        }
    }
}
