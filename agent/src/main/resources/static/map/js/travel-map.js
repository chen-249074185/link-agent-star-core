/* ===== 点亮地图核心（借鉴 traces-in-the-clouds footprintmap + plugin-visited，本项目精简版）=====
 * 依赖：高德 JS API（key/securityCode 从 /api/travel/map-config 下发，不写死在 HTML）
 * 数据：/api/travel/footprints（JWT 鉴权）→ 足迹点 → 射线法判定落在哪个省 → 该省点亮
 */
(function () {
  'use strict';

  const CONFIG = {
    MARKER_PRESETS: ['sunset', 'ocean', 'violet', 'forest', 'amber', 'citrus'],
    MARKER_STYLES: {
      sunset: 'linear-gradient(135deg, #ffb347, #ff6f61)',
      ocean: 'linear-gradient(135deg, #06beb6, #48b1bf)',
      violet: 'linear-gradient(135deg, #a18cd1, #fbc2eb)',
      forest: 'linear-gradient(135deg, #5ee7df, #39a37c)',
      amber: 'linear-gradient(135deg, #f6d365, #fda085)',
      citrus: 'linear-gradient(135deg, #fdfb8f, #a1ffce)'
    },
    LIT_STYLE: { strokeColor: '#5ee7df', strokeOpacity: 0.8, strokeWeight: 1, fillColor: '#06beb6', fillOpacity: 0.25 },
    DIM_STYLE: { strokeColor: '#ffffff', strokeOpacity: 0, strokeWeight: 0, fillColor: '#ffffff', fillOpacity: 0 }
  };

  const Utils = {
    escapeHtml: s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]),
    parseCoords: val => {
      if (typeof val === 'string') {
        const p = val.split(/[,，\s]+/).map(parseFloat).filter(n => !isNaN(n));
        return p.length >= 2 ? { lng: p[0], lat: p[1] } : null;
      }
      return null;
    },
    // 射线法：点是否在多边形内（geojson Polygon/MultiPolygon）
    isPointInGeoJSON: (pt, geometry) => {
      const p = [pt.lng, pt.lat];
      const pointInRing = (ring) => {
        let inside = false;
        for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
          const xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
          const intersect = ((yi > p[1]) !== (yj > p[1])) && (p[0] < (xj - xi) * (p[1] - yi) / (yj - yi) + xi);
          if (intersect) inside = !inside;
          if (Math.abs(p[0] - xi) < 0.0001 && Math.abs(p[1] - yi) < 0.0001) return true;
        }
        return inside;
      };
      const pointInPoly = (poly) => {
        if (!pointInRing(poly[0])) return false;
        for (let i = 1; i < poly.length; i++) if (pointInRing(poly[i])) return false;
        return true;
      };
      if (geometry.type === 'Polygon') return pointInPoly(geometry.coordinates);
      if (geometry.type === 'MultiPolygon') return geometry.coordinates.some(pointInPoly);
      return false;
    }
  };

  // 省份点亮：加载 geojson → 建省份多边形（默认透明）→ 足迹点判定点亮
  class ProvinceHighlighter {
    constructor(map, geojsonUrl, markerProvider) {
      this.map = map;
      this.geojsonUrl = geojsonUrl;
      this.markerProvider = markerProvider;
      this.polygons = [];
    }

    async init() {
      try {
        const res = await fetch(this.geojsonUrl);
        const geojson = await res.json();
        const features = geojson.features || (geojson.type === 'Feature' ? [geojson] : []);
        features.forEach(feature => {
          const props = feature.properties || {};
          const pName = String(props.name || props.ADMIN || props.name_zh || '').toLowerCase();
          // 只处理中国省份，world.geojson 里的中国板块跳过（本项目只用 provinces.geojson）
          if (['china', "people's republic of china", '中华人民共和国'].includes(pName)) return;
          const geom = feature.geometry;
          if (!geom) return;
          const coords = geom.type === 'Polygon' ? [geom.coordinates]
            : geom.type === 'MultiPolygon' ? geom.coordinates : [];
          coords.forEach(c => {
            const poly = new AMap.Polygon({
              path: c, cursor: 'default', bubble: true,
              strokeColor: CONFIG.DIM_STYLE.strokeColor, strokeOpacity: 0.01,
              strokeWeight: 0, fillColor: '#fff', fillOpacity: 0.01, zIndex: 1
            });
            poly._geom = geom;
            poly._name = props.name || props.ADMIN || '';
            this.polygons.push(poly);
          });
        });
        this.map.add(this.polygons);
        this.light();
      } catch (e) {
        console.warn('省份边界加载失败', e);
      }
    }

    // 点亮：足迹点所在省 → 青绿色填充
    light() {
      const markers = this.markerProvider();
      const lit = new Set();
      this.polygons.forEach(poly => {
        if (!poly._geom || lit.has(poly._geom)) return;
        if (markers.some(pt => Utils.isPointInGeoJSON(pt, poly._geom))) lit.add(poly._geom);
      });
      this.polygons.forEach(poly => {
        const isLit = lit.has(poly._geom);
        poly.setOptions(isLit ? CONFIG.LIT_STYLE : CONFIG.DIM_STYLE);
      });
    }
  }

  // 地图引擎：高德地图 + marker + InfoWindow
  class MapEngine {
    constructor(container, apiKey, securityCode) {
      this.container = container;
      this.apiKey = apiKey;
      this.securityCode = securityCode;
      this.map = null;
      this.markers = [];
      this.infoWindow = null;
    }

    async load() {
      if (window.AMap) return;
      return new Promise((resolve, reject) => {
        // 安全密钥必须在加载 JS API 之前设置（onload 里设置太晚，高德初始化时读不到）
        if (this.securityCode) window._AMapSecurityConfig = { securityJsCode: this.securityCode };
        const s = document.createElement('script');
        s.src = 'https://webapi.amap.com/maps?v=2.0&key=' + this.apiKey;
        s.onload = () => resolve();
        s.onerror = reject;
        document.head.appendChild(s);
      });
    }

    init(locations) {
      const div = document.createElement('div');
      div.className = 'travel-map-canvas';
      this.container.appendChild(div);
      this.map = new AMap.Map(div, {
        zoom: locations.length ? 4 : 5,
        center: locations.length ? [locations[0].lng, locations[0].lat] : [104.1954, 35.8617],
        mapStyle: 'amap://styles/whitesmoke',
        viewMode: '3D', pitch: 0, rotateEnable: false, pitchEnable: false
      });
      this.infoWindow = new AMap.InfoWindow({ anchor: 'bottom-center', offset: new AMap.Pixel(0, -10) });
      this.renderMarkers(locations);
      this.fitView(locations);
      return this;
    }

    renderMarkers(locations) {
      this.markers.forEach(m => this.map.remove(m));
      this.markers = [];
      locations.forEach((pt, i) => {
        const preset = pt.markerPreset || CONFIG.MARKER_PRESETS[i % CONFIG.MARKER_PRESETS.length];
        const marker = new AMap.Marker({
          position: [pt.lng, pt.lat],
          content: '<div class="travel-marker" style="background:' + (CONFIG.MARKER_STYLES[preset] || CONFIG.MARKER_STYLES.sunset) + '"></div>',
          offset: new AMap.Pixel(-9, -9),
          map: this.map
        });
        marker.on('click', () => {
          const h = Utils.escapeHtml;
          let html = '<div class="travel-popup"><h4>' + h(pt.name) + '</h4>';
          if (pt.date) html += '<p class="travel-popup-meta">' + h(pt.date) + '</p>';
          if (pt.province) html += '<p class="travel-popup-meta">' + h(pt.province) + '</p>';
          if (pt.description) html += '<p>' + h(pt.description) + '</p>';
          html += '</div>';
          this.infoWindow.setContent(html);
          this.infoWindow.open(this.map, [pt.lng, pt.lat]);
        });
        this.markers.push(marker);
      });
    }

    fitView(locations) {
      if (!locations.length) return;
      if (locations.length === 1) {
        this.map.setZoomAndCenter(10, [locations[0].lng, locations[0].lat]);
        return;
      }
      const poly = new AMap.Polyline({ path: locations.map(p => [p.lng, p.lat]), strokeOpacity: 0, map: this.map });
      this.map.setFitView([poly], false, [60, 80, 60, 80]);
      this.map.remove(poly);
    }
  }

  // 入口：container + token（JWT）→ 初始化地图
  window.TravelMap = {
    async bootstrap(container, token) {
      try {
        container.innerHTML = '<div class="travel-map-loading">地图加载中…</div>';
        // 1. 高德 JS key 从后端下发（不硬编码 HTML）
        const cfgRes = await fetch('/api/travel/map-config', { headers: { 'Authorization': 'Bearer ' + token } });
        const cfg = await cfgRes.json();
        if (cfg.code !== 0 || !cfg.data || !cfg.data.amapKey) {
          container.innerHTML = '<div class="travel-map-empty">地图 Key 未配置，请联系管理员在 .env 填写 AMAP_JS_KEY</div>';
          return;
        }
        // 2. 足迹点（JWT）
        const fpRes = await fetch('/api/travel/footprints', { headers: { 'Authorization': 'Bearer ' + token } });
        const fp = await fpRes.json();
        const raw = fp.code === 0 ? fp.data : [];
        const locations = raw.map((item, i) => {
          const coords = Utils.parseCoords(item.coordinates);
          if (!coords) return null;
          return {
            name: item.name || '未命名', lat: coords.lat, lng: coords.lng,
            province: item.province || '', date: item.date || '', description: item.description || '',
            markerPreset: item.markerPreset || CONFIG.MARKER_PRESETS[i % CONFIG.MARKER_PRESETS.length]
          };
        }).filter(Boolean);
        // 3. 初始化地图
        const engine = new MapEngine(container, cfg.data.amapKey, cfg.data.amapSecurityJsCode || '');
        await engine.load();
        engine.init(locations);
        // 4. 省份点亮（空足迹也加载边界，静默等待点亮）
        const hl = new ProvinceHighlighter(engine.map, '/map/data/provinces.geojson', () => locations);
        await hl.init();
        container.querySelector('.travel-map-loading')?.remove();
        window.TravelMap.__last = { engine, hl, locations };
        if (!locations.length) {
          container.querySelector('.travel-map-empty')?.remove();
        }
        return window.TravelMap.__last;
      } catch (e) {
        container.innerHTML = '<div class="travel-map-empty">地图加载失败：' + Utils.escapeHtml(e.message) + '</div>';
      }
    },
    // 发布/删除游记后刷新地图
    async refresh(container, token) {
      if (!window.TravelMap.__last) return;
      const fpRes = await fetch('/api/travel/footprints', { headers: { 'Authorization': 'Bearer ' + token } });
      const fp = await fpRes.json();
      const raw = fp.code === 0 ? fp.data : [];
      const locations = raw.map((item, i) => {
        const coords = Utils.parseCoords(item.coordinates);
        if (!coords) return null;
        return {
          name: item.name || '未命名', lat: coords.lat, lng: coords.lng,
          province: item.province || '', date: item.date || '', description: item.description || '',
          markerPreset: item.markerPreset || CONFIG.MARKER_PRESETS[i % CONFIG.MARKER_PRESETS.length]
        };
      }).filter(Boolean);
      window.TravelMap.__last.engine.renderMarkers(locations);
      window.TravelMap.__last.engine.fitView(locations);
      window.TravelMap.__last.hl.light();
      window.TravelMap.__last.locations = locations;
    }
  };
})();