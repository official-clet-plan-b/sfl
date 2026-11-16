import L from 'leaflet';
import { useEffect, useRef } from 'react';
import { fleetPaths } from 'shared/layout/navigation';
import {
  ageOf,
  compassPoint,
  MOVEMENT_COLOUR,
  MOVEMENT_LABEL,
  type LiveVehicle,
} from '../api/liveMapApi';

/** Greater Accra - where the map opens before any vehicle has a position. */
const DEFAULT_CENTRE: [number, number] = [5.6037, -0.187];

const escapeHtml = (value: string): string =>
  value.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c);

/**
 * A vehicle marker: a coloured disc with an arrow pointing the way it is travelling (moving) or a
 * plain dot (stopped / not reporting), and its registration underneath.
 */
const markerIcon = (vehicle: LiveVehicle, selected: boolean): L.DivIcon => {
  const colour = MOVEMENT_COLOUR[vehicle.movement];
  const ring = selected ? '0 0 0 4px rgba(12,74,110,0.35),' : '';
  const arrow =
    vehicle.movement === 'MOVING' && vehicle.headingDegrees !== null
      ? `<svg width="14" height="14" viewBox="0 0 24 24" style="transform:rotate(${vehicle.headingDegrees}deg)"><path d="M12 2l7 18-7-4-7 4z" fill="white"/></svg>`
      : '<span style="display:block;width:8px;height:8px;border-radius:50%;background:white"></span>';
  return L.divIcon({
    className: 'sfl-live-marker',
    html: `<div style="display:flex;flex-direction:column;align-items:center;gap:2px">
      <span style="display:flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:50%;background:${colour};border:2px solid white;box-shadow:${ring}0 1px 4px rgba(0,0,0,.35)">${arrow}</span>
      <span style="font:600 11px/1.2 Lato,system-ui,sans-serif;color:#101828;background:rgba(255,255,255,.92);padding:1px 5px;border-radius:4px;white-space:nowrap;box-shadow:0 1px 2px rgba(0,0,0,.2)">${escapeHtml(vehicle.registrationNumber)}</span>
    </div>`,
    iconSize: [80, 48],
    iconAnchor: [40, 14],
    popupAnchor: [0, -14],
  });
};

const popupHtml = (vehicle: LiveVehicle, basename: string): string => {
  const speed = vehicle.speedKph !== null ? `${Math.round(vehicle.speedKph)} km/h` : '-';
  const heading = compassPoint(vehicle.headingDegrees);
  const trip = vehicle.trip
    ? `<div style="margin-top:6px"><b>Trip ${escapeHtml(vehicle.trip.tripNumber)}</b><br/>${escapeHtml(vehicle.trip.origin)} &rarr; ${escapeHtml(vehicle.trip.destination)}${vehicle.trip.driverName ? `<br/>Driver: ${escapeHtml(vehicle.trip.driverName)}` : ''}</div>`
    : '<div style="margin-top:6px;color:#667085">No trip in progress</div>';
  return `<div style="font:13px/1.45 Lato,system-ui,sans-serif;min-width:190px">
    <div style="font-weight:700;font-size:14px">${escapeHtml(vehicle.registrationNumber)}</div>
    <div style="color:#475467">${escapeHtml(`${vehicle.make} ${vehicle.model}`)} &middot; ${escapeHtml(vehicle.siteCode)}</div>
    <div style="margin-top:6px"><span style="color:${MOVEMENT_COLOUR[vehicle.movement]};font-weight:700">${MOVEMENT_LABEL[vehicle.movement]}</span>${vehicle.movement === 'MOVING' ? ` &middot; ${speed}${heading ? ` heading ${heading}` : ''}` : ''}</div>
    <div style="color:#475467">Last report ${ageOf(vehicle.recordedAt)}${vehicle.sourceSystem ? ` via ${escapeHtml(vehicle.sourceSystem)}` : ''}</div>
    <div style="color:#667085;font-size:12px">${vehicle.latitude?.toFixed(5)}, ${vehicle.longitude?.toFixed(5)}</div>
    ${trip}
    <div style="margin-top:8px"><a href="${basename}${fleetPaths.vehicles}/${encodeURIComponent(vehicle.vehicleId)}">Open vehicle record &rarr;</a></div>
  </div>`;
};

interface FleetLiveMapProps {
  vehicles: LiveVehicle[];
  /** Pixel height of the map. */
  height?: number;
  selectedId?: string | null;
  onSelect?: (vehicleId: string) => void;
  /** Draw each vehicle's recent path. */
  showTrails?: boolean;
  /** Allow scroll-wheel zoom (off on the dashboard so the page scrolls normally). */
  scrollZoom?: boolean;
  /** Changing this re-frames the map on the vehicles shown - e.g. when a filter narrows them. */
  frameKey?: string;
}

/**
 * Every located vehicle on an OpenStreetMap base map, updated in place as new positions arrive -
 * markers move rather than redraw, so an open popup stays open across refreshes.
 */
const FleetLiveMap = ({
  vehicles,
  height = 520,
  selectedId = null,
  onSelect,
  showTrails = true,
  scrollZoom = true,
  frameKey = '',
}: FleetLiveMapProps) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const markersRef = useRef(new Map<string, L.Marker>());
  const trailsRef = useRef(new Map<string, L.Polyline>());
  const fittedRef = useRef(false);
  const frameKeyRef = useRef(frameKey);
  const onSelectRef = useRef(onSelect);
  useEffect(() => {
    onSelectRef.current = onSelect;
  }, [onSelect]);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) {
      return undefined;
    }
    const map = L.map(containerRef.current, { scrollWheelZoom: scrollZoom }).setView(DEFAULT_CENTRE, 11);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(map);
    mapRef.current = map;
    const markers = markersRef.current;
    const trails = trailsRef.current;
    return () => {
      map.remove();
      mapRef.current = null;
      markers.clear();
      trails.clear();
      fittedRef.current = false;
    };
  }, [scrollZoom]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) {
      return;
    }
    const basename = import.meta.env.BASE_URL.replace(/\/$/, '');
    const located = vehicles.filter((v) => v.latitude !== null && v.longitude !== null);
    const seen = new Set<string>();

    located.forEach((vehicle) => {
      seen.add(vehicle.vehicleId);
      const position: [number, number] = [vehicle.latitude as number, vehicle.longitude as number];
      const selected = vehicle.vehicleId === selectedId;
      let marker = markersRef.current.get(vehicle.vehicleId);
      if (marker) {
        marker.setLatLng(position);
        marker.setIcon(markerIcon(vehicle, selected));
        marker.setPopupContent(popupHtml(vehicle, basename));
      } else {
        marker = L.marker(position, { icon: markerIcon(vehicle, selected), title: vehicle.registrationNumber })
          .bindPopup(popupHtml(vehicle, basename))
          .addTo(map);
        marker.on('click', () => onSelectRef.current?.(vehicle.vehicleId));
        markersRef.current.set(vehicle.vehicleId, marker);
      }
      marker.setZIndexOffset(selected ? 1000 : vehicle.movement === 'MOVING' ? 500 : 0);

      const path = vehicle.trail.map((p) => [p.latitude, p.longitude] as [number, number]);
      const existing = trailsRef.current.get(vehicle.vehicleId);
      if (showTrails && path.length >= 2) {
        const style = { color: MOVEMENT_COLOUR[vehicle.movement], weight: selected ? 5 : 3, opacity: 0.7, dashArray: vehicle.movement === 'MOVING' ? undefined : '4 6' };
        if (existing) {
          existing.setLatLngs(path);
          existing.setStyle(style);
        } else {
          trailsRef.current.set(vehicle.vehicleId, L.polyline(path, style).addTo(map));
        }
      } else if (existing) {
        existing.remove();
        trailsRef.current.delete(vehicle.vehicleId);
      }
    });

    markersRef.current.forEach((marker, id) => {
      if (!seen.has(id)) {
        marker.remove();
        markersRef.current.delete(id);
      }
    });
    trailsRef.current.forEach((line, id) => {
      if (!seen.has(id)) {
        line.remove();
        trailsRef.current.delete(id);
      }
    });

    // Frame the fleet once, and again whenever a filter changes what is shown; between those the
    // operator owns the view, so a refresh never yanks the map away from where they were looking.
    if (frameKeyRef.current !== frameKey) {
      frameKeyRef.current = frameKey;
      fittedRef.current = false;
    }
    if (!fittedRef.current && located.length > 0) {
      const bounds = L.latLngBounds(located.map((v) => [v.latitude as number, v.longitude as number]));
      map.fitBounds(bounds, { padding: [48, 48], maxZoom: 15 });
      fittedRef.current = true;
    }
  }, [vehicles, selectedId, showTrails, frameKey]);

  // Fly to and open the selected vehicle.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !selectedId) {
      return;
    }
    const marker = markersRef.current.get(selectedId);
    if (marker) {
      map.flyTo(marker.getLatLng(), Math.max(map.getZoom(), 14), { duration: 0.6 });
      marker.openPopup();
    }
  }, [selectedId]);

  return (
    <div
      ref={containerRef}
      data-testid="fleet-live-map"
      className="w-full overflow-hidden rounded-xl border border-gray-200"
      style={{ height }}
    />
  );
};

export default FleetLiveMap;
