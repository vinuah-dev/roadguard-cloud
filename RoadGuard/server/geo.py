"""Small geodesy helpers shared by the API and the telemetry engine."""

import math
from typing import Optional, Tuple

EARTH_RADIUS_M = 6371000.0
METERS_PER_DEGREE_LAT = 111_320.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in meters between two WGS84 points."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return EARTH_RADIUS_M * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial bearing from point 1 to point 2, 0-360 degrees clockwise from north."""
    d_lon = math.radians(lon2 - lon1)
    p1, p2 = math.radians(lat1), math.radians(lat2)
    y = math.sin(d_lon) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(d_lon)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def approach_status(bearing: float, heading: Optional[float]) -> str:
    """APPROACHING when the hazard is within 65 degrees of the direction of travel."""
    if heading is None:
        return "PROXIMITY_ONLY"
    diff = abs((bearing - heading + 180) % 360 - 180)
    if diff <= 65:
        return "APPROACHING"
    return "RECEDING" if diff > 90 else "LATERAL"


def degree_pad(lat: float, radius_m: float) -> Tuple[float, float]:
    """Latitude/longitude half-widths of a bounding box that contains a circle of radius_m."""
    d_lat = radius_m / METERS_PER_DEGREE_LAT
    d_lon = radius_m / (METERS_PER_DEGREE_LAT * max(0.01, math.cos(math.radians(lat))))
    return d_lat, d_lon
