// Zero-dependency WebGL Studio 3D Hardware & Optical Light-Path Engine for Hongshu.
// Renders three sculpted 3D Android phones, a brushed-metal Private Hub monolith,
// an anodized Desktop Web/PWA Studio Display, Bezier light conduits, and gliding
// message capsules with scroll-choreographed spring camera physics.

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const lerp = (a, b, t) => a + (b - a) * t;
const smoothstep = (e0, e1, x) => {
  const t = clamp((x - e0) / (e1 - e0 || 1), 0, 1);
  return t * t * (3 - 2 * t);
};

// Minimal 4x4 column-major matrix utilities
function mat4Identity() {
  const m = new Float32Array(16);
  m[0] = m[5] = m[10] = m[15] = 1;
  return m;
}

function mat4Multiply(a, b) {
  const out = new Float32Array(16);
  for (let c = 0; c < 4; c++) {
    const b0 = b[c * 4], b1 = b[c * 4 + 1], b2 = b[c * 4 + 2], b3 = b[c * 4 + 3];
    out[c * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
    out[c * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
    out[c * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
    out[c * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
  }
  return out;
}

function mat4Perspective(fovRad, aspect, near, far) {
  const f = 1 / Math.tan(fovRad * 0.5);
  const nf = 1 / (near - far);
  const m = new Float32Array(16);
  m[0] = f / aspect;
  m[5] = f;
  m[10] = (far + near) * nf;
  m[11] = -1;
  m[14] = 2 * far * near * nf;
  return m;
}

function vec3Sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function vec3Cross(a, b) {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
}
function vec3Norm(v) {
  const len = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / len, v[1] / len, v[2] / len];
}

function mat4LookAt(eye, target, up = [0, 1, 0]) {
  const z = vec3Norm(vec3Sub(eye, target));
  const x = vec3Norm(vec3Cross(up, z));
  const y = vec3Cross(z, x);
  const m = new Float32Array(16);
  m[0] = x[0]; m[4] = x[1]; m[8] = x[2];
  m[1] = y[0]; m[5] = y[1]; m[9] = y[2];
  m[2] = z[0]; m[6] = z[1]; m[10] = z[2];
  m[12] = -(x[0] * eye[0] + x[1] * eye[1] + x[2] * eye[2]);
  m[13] = -(y[0] * eye[0] + y[1] * eye[1] + y[2] * eye[2]);
  m[14] = -(z[0] * eye[0] + z[1] * eye[1] + z[2] * eye[2]);
  m[15] = 1;
  return m;
}

function mat4Compose(pos, rotEuler, scale = [1, 1, 1]) {
  const [rx, ry, rz] = rotEuler;
  const cx = Math.cos(rx), sx = Math.sin(rx);
  const cy = Math.cos(ry), sy = Math.sin(ry);
  const cz = Math.cos(rz), sz = Math.sin(rz);
  // R = Ry * Rx * Rz
  const r00 = cy * cz + sy * sx * sz;
  const r01 = -cy * sz + sy * sx * cz;
  const r02 = sy * cx;
  const r10 = cx * sz;
  const r11 = cx * cz;
  const r12 = -sx;
  const r20 = -sy * cz + cy * sx * sz;
  const r21 = sy * sz + cy * sx * cz;
  const r22 = cy * cx;

  const m = new Float32Array(16);
  m[0] = r00 * scale[0]; m[1] = r10 * scale[0]; m[2] = r20 * scale[0]; m[3] = 0;
  m[4] = r01 * scale[1]; m[5] = r11 * scale[1]; m[6] = r21 * scale[1]; m[7] = 0;
  m[8] = r02 * scale[2]; m[9] = r12 * scale[2]; m[10] = r22 * scale[2]; m[11] = 0;
  m[12] = pos[0]; m[13] = pos[1]; m[14] = pos[2]; m[15] = 1;
  return m;
}

function bezierPoint(p0, p1, p2, p3, t) {
  const u = 1 - t;
  const b0 = u * u * u;
  const b1 = 3 * u * u * t;
  const b2 = 3 * u * t * t;
  const b3 = t * t * t;
  return [
    b0 * p0[0] + b1 * p1[0] + b2 * p2[0] + b3 * p3[0],
    b0 * p0[1] + b1 * p1[1] + b2 * p2[1] + b3 * p3[1],
    b0 * p0[2] + b1 * p1[2] + b2 * p2[2] + b3 * p3[2],
  ];
}

function bezierTangent(p0, p1, p2, p3, t) {
  const u = 1 - t;
  return vec3Norm([
    3 * u * u * (p1[0] - p0[0]) + 6 * u * t * (p2[0] - p1[0]) + 3 * t * t * (p3[0] - p2[0]),
    3 * u * u * (p1[1] - p0[1]) + 6 * u * t * (p2[1] - p1[1]) + 3 * t * t * (p3[1] - p2[1]),
    3 * u * u * (p1[2] - p0[2]) + 6 * u * t * (p2[2] - p1[2]) + 3 * t * t * (p3[2] - p2[2]),
  ]);
}

// Analytical CAD-grade filleted box with smooth C1 normals
function createRoundedBox(w, h, d, radius, segs = 14) {
  const hx = w * 0.5, hy = h * 0.5, hz = d * 0.5;
  const r = Math.min(radius, hx * 0.92, hy * 0.92, hz * 0.92);
  const ix = hx - r, iy = hy - r, iz = hz - r;

  // Cluster samples near [-1, 1] edges for crisp bevel highlights
  const steps = [];
  for (let i = 0; i <= segs; i++) {
    const t = i / segs;
    steps.push(-Math.cos(t * Math.PI));
  }

  const positions = [];
  const normals = [];
  const uvs = [];
  const indices = [];

  const faces = [
    { n: [0, 0, 1], u: [1, 0, 0], v: [0, 1, 0] },
    { n: [0, 0, -1], u: [-1, 0, 0], v: [0, 1, 0] },
    { n: [1, 0, 0], u: [0, 0, -1], v: [0, 1, 0] },
    { n: [-1, 0, 0], u: [0, 0, 1], v: [0, 1, 0] },
    { n: [0, 1, 0], u: [1, 0, 0], v: [0, 0, -1] },
    { n: [0, -1, 0], u: [1, 0, 0], v: [0, 0, 1] },
  ];

  const row = segs + 1;
  faces.forEach((face, fIdx) => {
    const base = fIdx * row * row;
    for (let j = 0; j <= segs; j++) {
      for (let i = 0; i <= segs; i++) {
        const su = steps[i], sv = steps[j];
        const qx = (face.n[0] + face.u[0] * su + face.v[0] * sv) * hx;
        const qy = (face.n[1] + face.u[1] * su + face.v[1] * sv) * hy;
        const qz = (face.n[2] + face.u[2] * su + face.v[2] * sv) * hz;

        const cx = clamp(qx, -ix, ix);
        const cy = clamp(qy, -iy, iy);
        const cz = clamp(qz, -iz, iz);

        let dx = qx - cx, dy = qy - cy, dz = qz - cz;
        const len = Math.hypot(dx, dy, dz);
        if (len > 1e-5) {
          dx /= len; dy /= len; dz /= len;
        } else {
          dx = face.n[0]; dy = face.n[1]; dz = face.n[2];
        }

        positions.push(cx + dx * r, cy + dy * r, cz + dz * r);
        normals.push(dx, dy, dz);
        uvs.push((su + 1) * 0.5, (sv + 1) * 0.5);
      }
    }
    for (let j = 0; j < segs; j++) {
      for (let i = 0; i < segs; i++) {
        const a = base + j * row + i;
        const b = a + 1;
        const c = a + row;
        const dIdx = c + 1;
        indices.push(a, b, dIdx, a, dIdx, c);
      }
    }
  });

  return {
    positions: new Float32Array(positions),
    normals: new Float32Array(normals),
    uvs: new Float32Array(uvs),
    indices: new Uint16Array(indices),
  };
}

function createQuad(w, h) {
  const hx = w * 0.5, hy = h * 0.5;
  return {
    positions: new Float32Array([-hx, -hy, 0, hx, -hy, 0, hx, hy, 0, -hx, hy, 0]),
    normals: new Float32Array([0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1]),
    uvs: new Float32Array([0, 1, 1, 1, 1, 0, 0, 0]),
    indices: new Uint16Array([0, 1, 2, 0, 2, 3]),
  };
}

// Tubular 3D cubic-Bezier optical light conduit
function createBezierTube(p0, p1, p2, p3, lengthSegs = 44, radialSegs = 8, radius = 0.018) {
  const positions = [];
  const normals = [];
  const uvs = [];
  const indices = [];

  for (let i = 0; i <= lengthSegs; i++) {
    const t = i / lengthSegs;
    const center = bezierPoint(p0, p1, p2, p3, t);
    const tangent = bezierTangent(p0, p1, p2, p3, t);
    const refUp = Math.abs(tangent[1]) > 0.92 ? [1, 0, 0] : [0, 1, 0];
    const normal = vec3Norm(vec3Cross(tangent, refUp));
    const binormal = vec3Norm(vec3Cross(tangent, normal));
    // Taper gently at both ends so entry into hardware looks seamless
    const taper = 0.45 + 0.55 * Math.sin(t * Math.PI);
    const r = radius * taper;

    for (let j = 0; j <= radialSegs; j++) {
      const phi = (j / radialSegs) * Math.PI * 2;
      const cp = Math.cos(phi), sp = Math.sin(phi);
      const nx = normal[0] * cp + binormal[0] * sp;
      const ny = normal[1] * cp + binormal[1] * sp;
      const nz = normal[2] * cp + binormal[2] * sp;
      positions.push(center[0] + nx * r, center[1] + ny * r, center[2] + nz * r);
      normals.push(nx, ny, nz);
      uvs.push(t, j / radialSegs);
    }
  }

  const ring = radialSegs + 1;
  for (let i = 0; i < lengthSegs; i++) {
    for (let j = 0; j < radialSegs; j++) {
      const a = i * ring + j;
      const b = a + ring;
      const c = b + 1;
      const d = a + 1;
      indices.push(a, b, c, a, c, d);
    }
  }

  return {
    positions: new Float32Array(positions),
    normals: new Float32Array(normals),
    uvs: new Float32Array(uvs),
    indices: new Uint16Array(indices),
  };
}

// Studio PBR-inspired Vertex & Fragment Shaders (WebGL1 compatible GLSL 100 so both WebGL2 and WebGL1 run identically)
const HARDWARE_VS = `
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUv;

uniform mat4 uModel;
uniform mat4 uViewProj;

varying vec3 vWorldPos;
varying vec3 vNormal;
varying vec2 vUv;

void main() {
  vec4 wp = uModel * vec4(aPos, 1.0);
  vWorldPos = wp.xyz;
  vNormal = normalize(mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz) * aNormal);
  vUv = aUv;
  gl_Position = uViewProj * wp;
}
`;

const HARDWARE_FS = `
precision highp float;

varying vec3 vWorldPos;
varying vec3 vNormal;
varying vec2 vUv;

uniform vec3 uCamPos;
uniform vec3 uBaseColor;
uniform vec3 uEmissive;
uniform float uMetallic;
uniform float uRoughness;
uniform float uOpacity;
uniform float uUseTex;
uniform float uMode; // 0: hardware/screen, 1: optical stream, 2: floor shadow+ripple
uniform float uTime;
uniform float uPulse;
uniform float uPacketT;
uniform float uDarkTheme;
uniform sampler2D uTex;

void main() {
  if (uMode > 1.5) {
    // Studio floor soft contact shadows + Private Hub harmonic arrival rings
    vec2 p = (vUv - 0.5) * vec2(8.8, 5.8);
    float dHub = length(p - vec2(0.0, 0.05));
    float dP1 = length((p - vec2(-1.68, 1.44)) * vec2(1.1, 1.35));
    float dP2 = length((p - vec2(-0.26, 1.92)) * vec2(1.1, 1.35));
    float dP3 = length((p - vec2(1.64, 1.40)) * vec2(1.1, 1.35));
    float dDesk = length((p - vec2(0.12, -1.62)) * vec2(0.55, 1.25));

    float shadow = exp(-dHub * dHub * 1.15) * 0.24
                 + exp(-dP1 * dP1 * 2.6) * 0.15
                 + exp(-dP2 * dP2 * 2.6) * 0.16
                 + exp(-dP3 * dP3 * 2.6) * 0.15
                 + exp(-dDesk * dDesk * 1.35) * 0.22;

    float ringRadius = 0.55 + uPulse * 2.55;
    float ring = exp(-pow((dHub - ringRadius) * 6.5, 2.0)) * (1.0 - uPulse) * step(0.01, uPulse);
    float idleHalo = exp(-pow((dHub - 0.78 - 0.06 * sin(uTime * 1.6)) * 8.0, 2.0)) * 0.22;

    vec3 shadowCol = mix(vec3(0.12, 0.18, 0.28), vec3(0.01, 0.02, 0.04), uDarkTheme);
    vec3 glowCol = mix(vec3(0.22, 0.44, 0.80), vec3(0.46, 0.68, 0.98), uDarkTheme) * idleHalo
                 + vec3(0.84, 0.65, 0.36) * ring * 0.85;
    float alpha = clamp(shadow * mix(0.68, 0.88, uDarkTheme) + ring * 0.42 + idleHalo * 0.32, 0.0, 0.72);
    vec3 finalFloor = mix(shadowCol, glowCol + shadowCol * 0.3, clamp((ring + idleHalo) * 1.6, 0.0, 1.0));
    gl_FragColor = vec4(finalFloor, alpha);
    return;
  }

  if (uMode > 0.5) {
    // Optical Bezier light stream shader
    float t = vUv.x;
    float wave = 0.5 + 0.5 * sin(t * 20.0 - uTime * 3.4);
    float packet = exp(-pow((t - uPacketT) * 9.5, 2.0)) * step(0.001, uPacketT) * step(uPacketT, 0.999);
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCamPos - vWorldPos);
    float rim = 0.45 + 0.55 * abs(dot(N, V));

    vec3 col = mix(uBaseColor, uEmissive, wave * 0.42 + packet * 0.92);
    col += vec3(1.0, 0.96, 0.88) * packet * 0.75;
    float edgeFade = smoothstep(0.0, 0.09, t) * smoothstep(1.0, 0.91, t);
    float alpha = (0.24 + wave * 0.16 + packet * 0.60) * edgeFade * rim * uOpacity;
    gl_FragColor = vec4(col, clamp(alpha, 0.0, 0.96));
    return;
  }

  // Studio PBR-inspired metallic / porcelain / glass surface
  vec3 N = normalize(vNormal);
  vec3 V = normalize(uCamPos - vWorldPos);

  vec3 texColor = uBaseColor;
  if (uUseTex > 0.5) {
    vec4 sampled = texture2D(uTex, vUv);
    texColor = sampled.rgb;
  }

  // Three-point warm consumer hardware studio lighting
  vec3 L1 = normalize(vec3(-0.52, 0.78, 0.62)); // Warm porcelain key light
  vec3 L2 = normalize(vec3(0.68, 0.42, 0.48));  // Mist-blue sky fill light
  vec3 L3 = normalize(vec3(0.0, 0.25, -0.85));  // Soft rim / backlight

  vec3 H1 = normalize(L1 + V);
  vec3 H2 = normalize(L2 + V);

  float diff1 = max(dot(N, L1), 0.0);
  float diff2 = max(dot(N, L2), 0.0);
  float rimLight = pow(1.0 - max(dot(N, V), 0.0), 3.2);

  float shininess = mix(18.0, 140.0, 1.0 - uRoughness);
  float spec1 = pow(max(dot(N, H1), 0.0), shininess);
  float spec2 = pow(max(dot(N, H2), 0.0), shininess * 0.7);

  // Hemisphere studio ambient
  vec3 skyAmb = mix(vec3(0.95, 0.96, 0.99), vec3(0.20, 0.25, 0.34), uDarkTheme);
  vec3 groundAmb = mix(vec3(0.86, 0.84, 0.80), vec3(0.09, 0.10, 0.13), uDarkTheme);
  vec3 ambient = mix(groundAmb, skyAmb, N.y * 0.5 + 0.5);

  vec3 diffuse = texColor * (ambient * 0.72 + vec3(1.0, 0.98, 0.94) * diff1 * 0.48 + vec3(0.82, 0.90, 1.0) * diff2 * 0.26);
  vec3 specTint = mix(vec3(1.0), texColor * 0.65 + vec3(0.38), uMetallic);
  vec3 specular = specTint * (spec1 * (0.28 + uMetallic * 0.55) + spec2 * (0.14 + uMetallic * 0.28));
  vec3 fresnel = mix(vec3(0.76, 0.85, 0.98), vec3(0.55, 0.72, 0.98), uDarkTheme) * rimLight * (0.16 + uMetallic * 0.22);

  // Subtle warm champagne point-light bounce from the Private Hub core
  vec3 toHub = vec3(0.0, -0.36, 0.05) - vWorldPos;
  float hubDist = length(toHub);
  float hubAtten = max(dot(N, normalize(toHub)), 0.0) / (1.0 + hubDist * hubDist * 1.8);
  vec3 hubBounce = vec3(0.92, 0.73, 0.44) * hubAtten * (0.14 + uPulse * 0.28);

  vec3 color = diffuse + specular + fresnel + hubBounce + uEmissive;

  // Screen surfaces preserve crisp UI contrast with subtle glass sheen
  if (uUseTex > 0.5) {
    float glassSheen = pow(max(dot(N, normalize(vec3(-0.35, 0.55, 0.75) + V)), 0.0), 42.0) * 0.12;
    color = texColor * 0.97 + vec3(glassSheen) + fresnel * 0.28;
  }

  gl_FragColor = vec4(color, uOpacity);
}
`;

// Helper: draw rounded rect on 2D canvas
function roundRect(ctx, x, y, w, h, r) {
  const rad = Math.min(r, w * 0.5, h * 0.5);
  ctx.beginPath();
  ctx.moveTo(x + rad, y);
  ctx.arcTo(x + w, y, x + w, y + h, rad);
  ctx.arcTo(x + w, y + h, x, y + h, rad);
  ctx.arcTo(x, y + h, x, y, rad);
  ctx.arcTo(x, y, x + w, y, rad);
  ctx.closePath();
}

// Crisp dynamic Android M3 Expressive screen texture painter (384 x 768)
function paintPhoneScreen(canvas, spec, darkTheme) {
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);

  const bg = darkTheme ? "#12151c" : "#f6f7fb";
  const card = darkTheme ? "#1e232d" : "#ffffff";
  const pill = darkTheme ? "#262c38" : "#e9eef7";
  const ink = darkTheme ? "#eef1f8" : "#18202c";
  const muted = darkTheme ? "#9aa3b5" : "#5c6678";
  const accent = spec.accent || "#2558b0";
  const chipBg = darkTheme ? "#1b3a66" : "#d8e6fc";
  const chipInk = darkTheme ? "#cbe0ff" : "#0b3c8a";

  // Screen background with rounded display corners
  roundRect(ctx, 2, 2, W - 4, H - 4, 46);
  ctx.fillStyle = bg;
  ctx.fill();

  // Status bar + punch-hole camera
  ctx.fillStyle = muted;
  ctx.font = "600 18px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("11:48", 34, 40);
  ctx.fillStyle = darkTheme ? "#090b0f" : "#1a1f29";
  ctx.beginPath();
  ctx.arc(W * 0.5, 32, 10, 0, Math.PI * 2);
  ctx.fill();

  // Signal + battery
  roundRect(ctx, W - 78, 25, 42, 16, 5);
  ctx.strokeStyle = muted;
  ctx.lineWidth = 2;
  ctx.stroke();
  roundRect(ctx, W - 75, 28, 30, 10, 3);
  ctx.fillStyle = "#2f9e5b";
  ctx.fill();

  // Hongshu M3 Pill Search Header
  roundRect(ctx, 22, 62, W - 44, 58, 29);
  ctx.fillStyle = pill;
  ctx.fill();
  ctx.fillStyle = accent;
  ctx.beginPath();
  ctx.arc(52, 91, 9, 0, Math.PI * 2);
  ctx.strokeStyle = accent;
  ctx.lineWidth = 2.6;
  ctx.stroke();
  ctx.fillStyle = muted;
  ctx.font = "500 19px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("在鸿枢中搜索", 74, 98);

  // Device & SIM status pill
  roundRect(ctx, 22, 134, W - 44, 42, 14);
  ctx.fillStyle = chipBg;
  ctx.fill();
  ctx.fillStyle = chipInk;
  ctx.font = "600 16px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(`${spec.title} · ${spec.simBadge}`, 36, 161);

  // Group Filter Chips
  const groups = ["全部 18", "验证码 4", "其他 14"];
  let gx = 22;
  groups.forEach((g, idx) => {
    const gw = idx === 1 ? 104 : 94;
    roundRect(ctx, gx, 190, gw, 36, 18);
    ctx.fillStyle = idx === spec.activeChip ? accent : card;
    ctx.fill();
    ctx.fillStyle = idx === spec.activeChip ? "#ffffff" : muted;
    ctx.font = "600 15px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(g, gx + 16, 214);
    gx += gw + 10;
  });

  // Featured Live Incoming Card (highlighted on arrival)
  const highlight = spec.flash > 0.02;
  roundRect(ctx, 18, 244, W - 36, 174, 28);
  ctx.fillStyle = highlight
    ? (darkTheme ? "#203352" : "#eaf2ff")
    : card;
  ctx.fill();
  ctx.strokeStyle = highlight ? accent : (darkTheme ? "#2d3545" : "#dfe5f0");
  ctx.lineWidth = highlight ? 3 : 1.5;
  ctx.stroke();

  // Sender badge + timestamp
  ctx.beginPath();
  ctx.arc(52, 284, 20, 0, Math.PI * 2);
  ctx.fillStyle = accent;
  ctx.fill();
  ctx.fillStyle = "#ffffff";
  ctx.font = "700 18px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(spec.senderInitial, 43, 291);

  ctx.fillStyle = ink;
  ctx.font = "700 20px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(spec.senderName, 84, 282);
  ctx.fillStyle = muted;
  ctx.font = "500 14px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(`${spec.simLine} · 刚刚同步`, 84, 304);

  if (spec.otp) {
    roundRect(ctx, 36, 322, W - 72, 52, 16);
    ctx.fillStyle = chipBg;
    ctx.fill();
    ctx.fillStyle = chipInk;
    ctx.font = "700 26px ui-monospace, 'SF Mono', monospace";
    ctx.fillText(spec.otp, 52, 357);
    ctx.font = "600 15px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText("复制验证码", W - 134, 354);
  } else {
    ctx.fillStyle = ink;
    ctx.font = "500 17px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(spec.snippet1, 36, 348);
    ctx.fillStyle = muted;
    ctx.font = "500 16px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(spec.snippet2, 36, 376);
  }

  ctx.fillStyle = "#2f9e5b";
  ctx.font = "600 14px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(`● outbox → 中枢已确认 #${spec.seq}`, 36, 402);

  // Secondary conversation rows
  const subRows = spec.rows || [];
  let ry = 436;
  subRows.forEach((r) => {
    roundRect(ctx, 18, ry, W - 36, 92, 24);
    ctx.fillStyle = card;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(50, ry + 46, 18, 0, Math.PI * 2);
    ctx.fillStyle = r.color;
    ctx.fill();
    ctx.fillStyle = "#ffffff";
    ctx.font = "700 16px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(r.initial, 42, ry + 52);

    ctx.fillStyle = ink;
    ctx.font = "600 18px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(r.title, 80, ry + 38);
    ctx.fillStyle = muted;
    ctx.font = "400 15px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(r.body, 80, ry + 66);
    ry += 104;
  });

  // Bottom navigation bar indicator
  roundRect(ctx, W * 0.34, H - 22, W * 0.32, 7, 4);
  ctx.fillStyle = darkTheme ? "#4d5668" : "#b8c0cf";
  ctx.fill();
}

// Crisp dynamic Desktop Web/PWA Two-Pane Inbox texture painter (1024 x 640)
function paintDesktopScreen(canvas, state, darkTheme) {
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);

  const shellBg = darkTheme ? "#11141b" : "#eef1f7";
  const paneBg = darkTheme ? "#1a1f29" : "#ffffff";
  const rowSel = darkTheme ? "#223759" : "#dce8fc";
  const bubbleBg = darkTheme ? "#262d3b" : "#f1f4fa";
  const ink = darkTheme ? "#edf1f8" : "#18202c";
  const muted = darkTheme ? "#95a0b4" : "#5b6577";
  const accent = darkTheme ? "#8ab8ff" : "#1e56b5";
  const otpBg = darkTheme ? "#173257" : "#e5effe";

  roundRect(ctx, 0, 0, W, H, 22);
  ctx.fillStyle = shellBg;
  ctx.fill();

  // Top macOS/PWA window bar
  ctx.fillStyle = darkTheme ? "#171b24" : "#e3e8f1";
  roundRect(ctx, 0, 0, W, 44, 22);
  ctx.fill();
  ["#e56b65", "#e4b354", "#54b373"].forEach((c, i) => {
    ctx.beginPath();
    ctx.arc(26 + i * 20, 22, 6, 0, Math.PI * 2);
    ctx.fillStyle = c;
    ctx.fill();
  });
  ctx.fillStyle = muted;
  ctx.font = "600 15px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("鸿枢 · 短信中枢 (Web / PWA · 自托管私有实例)", 96, 27);

  ctx.fillStyle = "#2f9e5b";
  ctx.font = "600 14px ui-monospace, 'SF Mono', monospace";
  ctx.fillText(`● WS 在线 · sync_clock #${state.seq}`, W - 248, 27);

  // Left Inbox Column (width 370)
  roundRect(ctx, 18, 56, 368, H - 74, 24);
  ctx.fillStyle = paneBg;
  ctx.fill();

  // Pill search inside left pane
  roundRect(ctx, 34, 74, 336, 44, 22);
  ctx.fillStyle = shellBg;
  ctx.fill();
  ctx.fillStyle = muted;
  ctx.font = "500 15px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("⌕ 搜索短信、号码或中枢别名", 52, 102);

  // Filter chips
  const chips = ["筛选 · 3 台手机", "全部短信 18", "验证码 4"];
  let cx = 34;
  chips.forEach((ch, idx) => {
    const cw = idx === 0 ? 122 : 100;
    roundRect(ctx, cx, 130, cw, 30, 15);
    ctx.fillStyle = idx === 2 ? otpBg : shellBg;
    ctx.fill();
    ctx.fillStyle = idx === 2 ? accent : muted;
    ctx.font = "600 13px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(ch, cx + 12, 150);
    cx += cw + 8;
  });

  // Conversation rows
  const items = state.items || [];
  items.slice(0, 4).forEach((item, idx) => {
    const y = 174 + idx * 96;
    roundRect(ctx, 30, y, 344, 84, 22);
    ctx.fillStyle = idx === 0 ? rowSel : "transparent";
    ctx.fill();

    ctx.beginPath();
    ctx.arc(62, y + 42, 20, 0, Math.PI * 2);
    ctx.fillStyle = item.color;
    ctx.fill();
    ctx.fillStyle = "#ffffff";
    ctx.font = "700 17px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(item.initial, 53, y + 48);

    ctx.fillStyle = ink;
    ctx.font = "700 17px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(item.sender, 94, y + 34);
    ctx.fillStyle = muted;
    ctx.font = "500 13px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText(item.deviceBadge, 248, y + 33);

    if (item.otp) {
      roundRect(ctx, 94, y + 46, 110, 24, 8);
      ctx.fillStyle = accent;
      ctx.fill();
      ctx.fillStyle = darkTheme ? "#0c1c36" : "#ffffff";
      ctx.font = "700 13px ui-monospace, 'SF Mono', monospace";
      ctx.fillText(`验证码 ${item.otp}`, 102, y + 63);
      ctx.fillStyle = muted;
      ctx.font = "400 14px -apple-system, 'PingFang SC', sans-serif";
      ctx.fillText(item.preview, 212, y + 63);
    } else {
      ctx.fillStyle = muted;
      ctx.font = "400 14px -apple-system, 'PingFang SC', sans-serif";
      ctx.fillText(item.preview, 94, y + 62);
    }
  });

  // Right Floating Detail Pane
  const dx = 402, dw = W - 420;
  roundRect(ctx, dx, 56, dw, H - 74, 26);
  ctx.fillStyle = paneBg;
  ctx.fill();

  const top = items[0] || {
    sender: "星云通行证",
    sub: "1069070021 · 来自 3 台授权手机汇聚",
    otp: "684219",
    body: "【星云通行证】您的登录验证码为 684219，5 分钟内有效。请勿向他人泄露验证码。",
    deviceBadge: "Xiaomi 14 · 186****9105",
  };

  // Detail Header
  ctx.fillStyle = ink;
  ctx.font = "700 22px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(top.sender, dx + 32, 102);
  ctx.fillStyle = muted;
  ctx.font = "500 14px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(`${top.deviceBadge} · 只读安全归档 · MySQL utf8mb4_bin`, dx + 32, 126);

  ctx.strokeStyle = darkTheme ? "#283040" : "#e6ebf3";
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.moveTo(dx + 24, 146);
  ctx.lineTo(dx + dw - 24, 146);
  ctx.stroke();

  // Day pill
  roundRect(ctx, dx + dw * 0.5 - 48, 164, 96, 28, 14);
  ctx.fillStyle = shellBg;
  ctx.fill();
  ctx.fillStyle = muted;
  ctx.font = "600 13px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("今天 · 实时", dx + dw * 0.5 - 32, 183);

  // Earlier message bubble
  roundRect(ctx, dx + 32, 212, dw - 110, 84, 22);
  ctx.fillStyle = bubbleBg;
  ctx.fill();
  ctx.fillStyle = ink;
  ctx.font = "400 16px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("【系统通知】跨设备授权令牌校验正常，新消息到达将实时推送。", dx + 52, 250);
  ctx.fillStyle = muted;
  ctx.font = "500 13px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("11:20 · Pixel 9 主力机 · SIM1 138****0412", dx + 52, 278);

  // Featured Latest Message Bubble + OTP Extraction Card
  roundRect(ctx, dx + 32, 318, dw - 84, 214, 26);
  ctx.fillStyle = state.flash > 0.05 ? otpBg : bubbleBg;
  ctx.fill();
  ctx.strokeStyle = accent;
  ctx.lineWidth = state.flash > 0.05 ? 2.5 : 1;
  ctx.stroke();

  if (top.otp) {
    roundRect(ctx, dx + 52, 338, dw - 124, 94, 18);
    ctx.fillStyle = paneBg;
    ctx.fill();
    ctx.fillStyle = muted;
    ctx.font = "600 14px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText("提取验证码", dx + 72, 366);
    ctx.fillStyle = "#2f9e5b";
    ctx.fillText("有效至 11:53", dx + dw - 196, 366);

    ctx.fillStyle = ink;
    ctx.font = "700 36px ui-monospace, 'SF Mono', monospace";
    ctx.fillText(top.otp, dx + 72, 412);

    roundRect(ctx, dx + dw - 204, 380, 112, 38, 19);
    ctx.fillStyle = accent;
    ctx.fill();
    ctx.fillStyle = darkTheme ? "#091830" : "#ffffff";
    ctx.font = "700 15px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText("一键复制", dx + dw - 178, 404);
  }

  ctx.fillStyle = ink;
  ctx.font = "400 16px -apple-system, 'PingFang SC', sans-serif";
  const bodySlice = top.body.length > 34 ? top.body.slice(0, 34) + "…" : top.body;
  ctx.fillText(bodySlice, dx + 52, top.otp ? 468 : 390);
  ctx.fillStyle = muted;
  ctx.font = "500 13px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(`刚刚 · 来自 ${top.deviceBadge} · SHA-256 指纹去重已确认`, dx + 52, top.otp ? 506 : 438);
}

// Hub Top Medallion Texture (512 x 512)
function paintHubMedallion(canvas, seq, darkTheme) {
  const ctx = canvas.getContext("2d");
  const S = canvas.width;
  ctx.clearRect(0, 0, S, S);
  roundRect(ctx, 0, 0, S, S, 72);
  ctx.fillStyle = darkTheme ? "#131822" : "#1c2432";
  ctx.fill();

  ctx.strokeStyle = "rgba(218, 173, 104, 0.52)";
  ctx.lineWidth = 4;
  ctx.beginPath();
  ctx.arc(S * 0.5, S * 0.5, 168, 0, Math.PI * 2);
  ctx.stroke();

  ctx.strokeStyle = "rgba(122, 174, 248, 0.38)";
  ctx.setLineDash([8, 10]);
  ctx.beginPath();
  ctx.arc(S * 0.5, S * 0.5, 198, 0, Math.PI * 2);
  ctx.stroke();
  ctx.setLineDash([]);

  const grad = ctx.createRadialGradient(S * 0.5, S * 0.5, 8, S * 0.5, S * 0.5, 68);
  grad.addColorStop(0, "#fff3d6");
  grad.addColorStop(0.55, "#d8a65c");
  grad.addColorStop(1, "rgba(180, 126, 52, 0)");
  ctx.fillStyle = grad;
  ctx.beginPath();
  ctx.arc(S * 0.5, S * 0.5, 68, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = "#f4f0e6";
  ctx.textAlign = "center";
  ctx.font = "700 26px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText("鸿枢 · PRIVATE HUB", S * 0.5, 134);
  ctx.fillStyle = "#9fb4d4";
  ctx.font = "600 20px ui-monospace, 'SF Mono', monospace";
  ctx.fillText("Go + MySQL 8.4", S * 0.5, 386);
  ctx.fillStyle = "#e7be7e";
  ctx.fillText(`sync_clock #${seq}`, S * 0.5, 418);
  ctx.textAlign = "start";
}

// Floating 3D Message Capsule Texture (256 x 128)
function paintPacketCard(canvas, label, code) {
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  roundRect(ctx, 3, 3, W - 6, H - 6, 26);
  ctx.fillStyle = "#ffffff";
  ctx.fill();
  ctx.strokeStyle = "#3b73c9";
  ctx.lineWidth = 4;
  ctx.stroke();

  ctx.fillStyle = "#1d52a8";
  ctx.font = "700 20px -apple-system, 'PingFang SC', sans-serif";
  ctx.fillText(label, 22, 46);
  ctx.fillStyle = "#18202c";
  ctx.font = "700 30px ui-monospace, 'SF Mono', monospace";
  ctx.fillText(code, 22, 92);
}

// Camera Keyframes for Hero + 3 Scroll Stages
const CAMERA_POSES = [
  // 0: Hero panoramic overview (all 3 phones + Private Hub + Desktop Inbox)
  { eye: [0.0, 1.18, 6.28], target: [0.0, 0.12, -0.08], fov: 37.5 },
  // 1: Stage 01 — Multi-SIM Android Phones close-up
  { eye: [-0.88, 0.44, 4.48], target: [-0.30, -0.26, 1.35], fov: 34 },
  // 2: Stage 02 — Private Self-Hosted Hub monolith convergence
  { eye: [1.18, 1.48, 2.98], target: [0.0, -0.36, 0.05], fov: 33 },
  // 3: Stage 03 — Full Desktop Web/PWA Studio Display framed cleanly
  { eye: [0.12, 0.80, 3.45], target: [0.12, 0.78, -1.62], fov: 35.5 },
];

function sampleCameraPose(stageValue) {
  const v = clamp(stageValue, 0, CAMERA_POSES.length - 1);
  const idx = Math.min(Math.floor(v), CAMERA_POSES.length - 2);
  const frac = smoothstep(0, 1, v - idx);
  const a = CAMERA_POSES[idx];
  const b = CAMERA_POSES[idx + 1];
  return {
    eye: [lerp(a.eye[0], b.eye[0], frac), lerp(a.eye[1], b.eye[1], frac), lerp(a.eye[2], b.eye[2], frac)],
    target: [lerp(a.target[0], b.target[0], frac), lerp(a.target[1], b.target[1], frac), lerp(a.target[2], b.target[2], frac)],
    fov: lerp(a.fov, b.fov, frac),
  };
}

export class HongshuStage3D {
  constructor(canvas, options = {}) {
    this.canvas = canvas;
    this.onTelemetry = options.onTelemetry || (() => {});
    this.reducedMotion = Boolean(options.reducedMotion);
    this.darkTheme = Boolean(options.darkTheme);

    this.targetStage = 0;
    this.currentStage = 0;
    this.stageVel = 0;

    this.pointerX = 0;
    this.pointerY = 0;
    this.smoothPtrX = 0;
    this.smoothPtrY = 0;

    this.seq = 1041;
    this.hubPulse = 0;
    this.time = 0;
    this.lastTs = 0;
    this.visible = true;
    this.rafId = 0;

    // Active packet animation along phone[idx] -> hub -> desktop
    this.packet = {
      phoneIndex: 1,
      progress: 0.22, // 0..1 phone->hub, 1..2 hub->desktop
      active: true,
      label: "验证码同步",
      code: "684219",
    };

    this.phoneSpecs = [
      {
        title: "Pixel 9 主力机",
        simBadge: "SIM1 · 0412",
        simLine: "138****0412",
        accent: "#2558b0",
        activeChip: 1,
        senderInitial: "星",
        senderName: "星云通行证",
        otp: "684219",
        seq: 1041,
        flash: 0,
        rows: [
          { initial: "林", title: "林知夏 (本机匹配)", body: "下午三点阳光会议室见", color: "#2f9e5b" },
          { initial: "银", title: "示例银行 95508", body: "储蓄账户自动扣款 18.00 元", color: "#d9883d" },
        ],
      },
      {
        title: "Xiaomi 14 备用机",
        simBadge: "双卡 · 9105 / 7731",
        simLine: "186****9105",
        accent: "#2d749c",
        activeChip: 0,
        senderInitial: "青",
        senderName: "青鸟驿站",
        otp: "",
        snippet1: "澄园北门服务站包裹已到站",
        snippet2: "凭取件码 4-2-1086 取件",
        seq: 1039,
        flash: 0,
        rows: [
          { initial: "书", title: "云栖书店会员", body: "秋季纸质设计年鉴展本周六开启", color: "#8a46a8" },
          { initial: "流", title: "运营商流量提醒", body: "本月共享流量包剩余 24.6 GB", color: "#1496a8" },
        ],
      },
      {
        title: "Galaxy S24 工作机",
        simBadge: "工作卡 · 5520",
        simLine: "139****5520",
        accent: "#8f6b39",
        activeChip: 1,
        senderInitial: "研",
        senderName: "内网研发网关",
        otp: "903-741",
        seq: 1036,
        flash: 0,
        rows: [
          { initial: "电", title: "通话记录 · 呼入", body: "园区物业前台 · 2 分 18 秒", color: "#5263b8" },
          { initial: "周", title: "周予安", body: "海外卡动态码在桌面端直接复制到了", color: "#de5b54" },
        ],
      },
    ];

    this.desktopState = {
      seq: 1041,
      flash: 0,
      items: [
        {
          sender: "星云通行证",
          initial: "星",
          color: "#2558b0",
          otp: "684219",
          preview: "5 分钟内有效",
          body: "【星云通行证】您的登录验证码为 684219，5 分钟内有效。",
          deviceBadge: "Pixel 9 · 0412",
        },
        {
          sender: "青鸟驿站",
          initial: "青",
          color: "#2d749c",
          otp: "",
          preview: "取件码 4-2-1086 澄园北门站",
          body: "【青鸟驿站】您的包裹已到澄园北门服务站，取件码 4-2-1086。",
          deviceBadge: "Xiaomi 14 · 9105",
        },
        {
          sender: "内网研发网关",
          initial: "研",
          color: "#8f6b39",
          otp: "903-741",
          preview: "SSH 堡垒机二次验证",
          body: "【内网研发网关】SSH 堡垒机二次验证动态码 903-741。",
          deviceBadge: "Galaxy S24 · 5520",
        },
        {
          sender: "林知夏",
          initial: "林",
          color: "#2f9e5b",
          otp: "",
          preview: "我：收到，备份脚本跑完就过去",
          body: "下午三点的建筑模型评审改到二楼阳光会议室啦。",
          deviceBadge: "Pixel 9 · 0412",
        },
      ],
    };

    this.phoneTransforms = [
      { pos: [-1.68, -0.22, 1.44], rot: [-0.17, 0.36, 0.04], kick: 0 },
      { pos: [-0.26, -0.50, 1.92], rot: [-0.26, 0.10, 0.015], kick: 0 },
      { pos: [1.64, -0.26, 1.40], rot: [-0.18, -0.38, -0.04], kick: 0 },
    ];

    this.phonePaths = [
      [[-1.60, -0.02, 1.32], [-1.18, 0.54, 0.90], [-0.52, 0.34, 0.38], [-0.12, -0.34, 0.08]],
      [[-0.25, -0.26, 1.78], [-0.18, 0.34, 1.20], [-0.08, 0.24, 0.52], [0.0, -0.34, 0.12]],
      [[1.56, -0.05, 1.28], [1.14, 0.50, 0.86], [0.50, 0.32, 0.38], [0.12, -0.34, 0.08]],
    ];
    this.hubToDesktopPath = [
      [0.0, -0.30, 0.02],
      [0.04, 0.38, -0.36],
      [0.08, 0.78, -0.98],
      [0.12, 0.88, -1.52],
    ];

    this.gl = null;
    this.fallback2d = false;
    this.init();
  }

  init() {
    const glOpts = {
      antialias: true,
      alpha: true,
      premultipliedAlpha: false,
      powerPreference: "high-performance",
    };
    const gl =
      this.canvas.getContext("webgl2", glOpts) ||
      this.canvas.getContext("webgl", glOpts);

    if (!gl) {
      this.fallback2d = true;
      this.resize();
      this.renderFallback2D();
      return;
    }
    this.gl = gl;
    this.program = this.createProgram(HARDWARE_VS, HARDWARE_FS);
    if (!this.program) {
      this.fallback2d = true;
      this.resize();
      this.renderFallback2D();
      return;
    }

    this.locs = {
      aPos: gl.getAttribLocation(this.program, "aPos"),
      aNormal: gl.getAttribLocation(this.program, "aNormal"),
      aUv: gl.getAttribLocation(this.program, "aUv"),
      uModel: gl.getUniformLocation(this.program, "uModel"),
      uViewProj: gl.getUniformLocation(this.program, "uViewProj"),
      uCamPos: gl.getUniformLocation(this.program, "uCamPos"),
      uBaseColor: gl.getUniformLocation(this.program, "uBaseColor"),
      uEmissive: gl.getUniformLocation(this.program, "uEmissive"),
      uMetallic: gl.getUniformLocation(this.program, "uMetallic"),
      uRoughness: gl.getUniformLocation(this.program, "uRoughness"),
      uOpacity: gl.getUniformLocation(this.program, "uOpacity"),
      uUseTex: gl.getUniformLocation(this.program, "uUseTex"),
      uMode: gl.getUniformLocation(this.program, "uMode"),
      uTime: gl.getUniformLocation(this.program, "uTime"),
      uPulse: gl.getUniformLocation(this.program, "uPulse"),
      uPacketT: gl.getUniformLocation(this.program, "uPacketT"),
      uDarkTheme: gl.getUniformLocation(this.program, "uDarkTheme"),
      uTex: gl.getUniformLocation(this.program, "uTex"),
    };

    // Build GPU Meshes
    this.meshes = {
      phoneBody: this.uploadMesh(createRoundedBox(0.76, 1.54, 0.085, 0.10, 12)),
      phoneScreen: this.uploadMesh(createQuad(0.69, 1.44)),
      hubChassis: this.uploadMesh(createRoundedBox(1.52, 0.30, 1.52, 0.14, 14)),
      hubTopPlate: this.uploadMesh(createQuad(1.16, 1.16)),
      deskBody: this.uploadMesh(createRoundedBox(3.76, 2.38, 0.10, 0.06, 12)),
      deskStandNeck: this.uploadMesh(createRoundedBox(0.38, 1.05, 0.08, 0.03, 8)),
      deskStandFoot: this.uploadMesh(createRoundedBox(1.06, 0.05, 0.68, 0.024, 8)),
      deskScreen: this.uploadMesh(createQuad(3.56, 2.20)),
      packetCard: this.uploadMesh(createRoundedBox(0.54, 0.27, 0.026, 0.04, 8)),
      packetFace: this.uploadMesh(createQuad(0.50, 0.23)),
      floorQuad: this.uploadMesh(createQuad(8.8, 5.8)),
      phoneTubes: this.phonePaths.map((pts) =>
        this.uploadMesh(createBezierTube(pts[0], pts[1], pts[2], pts[3], 42, 8, 0.019)),
      ),
      hubDeskTube: this.uploadMesh(
        createBezierTube(
          this.hubToDesktopPath[0],
          this.hubToDesktopPath[1],
          this.hubToDesktopPath[2],
          this.hubToDesktopPath[3],
          44,
          8,
          0.024,
        ),
      ),
    };

    // Offscreen 2D canvases for dynamic crisp UI textures
    this.phoneCanvases = this.phoneSpecs.map(() => {
      const c = document.createElement("canvas");
      c.width = 384;
      c.height = 768;
      return c;
    });
    this.desktopCanvas = document.createElement("canvas");
    this.desktopCanvas.width = 1024;
    this.desktopCanvas.height = 640;

    this.hubCanvas = document.createElement("canvas");
    this.hubCanvas.width = 512;
    this.hubCanvas.height = 512;

    this.packetCanvas = document.createElement("canvas");
    this.packetCanvas.width = 256;
    this.packetCanvas.height = 128;

    this.phoneTextures = this.phoneCanvases.map(() => this.createTexture());
    this.desktopTexture = this.createTexture();
    this.hubTexture = this.createTexture();
    this.packetTexture = this.createTexture();

    this.refreshAllTextures();
    this.resize();
    this.start();
  }

  createShader(type, source) {
    const gl = this.gl;
    const s = gl.createShader(type);
    gl.shaderSource(s, source);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      gl.deleteShader(s);
      return null;
    }
    return s;
  }

  createProgram(vsSrc, fsSrc) {
    const gl = this.gl;
    const vs = this.createShader(gl.VERTEX_SHADER, vsSrc);
    const fs = this.createShader(gl.FRAGMENT_SHADER, fsSrc);
    if (!vs || !fs) return null;
    const prog = gl.createProgram();
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return null;
    return prog;
  }

  uploadMesh(geo) {
    const gl = this.gl;
    const posBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, posBuf);
    gl.bufferData(gl.ARRAY_BUFFER, geo.positions, gl.STATIC_DRAW);

    const normBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, normBuf);
    gl.bufferData(gl.ARRAY_BUFFER, geo.normals, gl.STATIC_DRAW);

    const uvBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, uvBuf);
    gl.bufferData(gl.ARRAY_BUFFER, geo.uvs, gl.STATIC_DRAW);

    const idxBuf = gl.createBuffer();
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, idxBuf);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, geo.indices, gl.STATIC_DRAW);

    return { posBuf, normBuf, uvBuf, idxBuf, count: geo.indices.length };
  }

  createTexture() {
    const gl = this.gl;
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    return tex;
  }

  updateTexture(tex, canvas) {
    if (!this.gl) return;
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
  }

  refreshAllTextures() {
    if (this.fallback2d) {
      this.renderFallback2D();
      return;
    }
    this.phoneSpecs.forEach((spec, idx) => {
      paintPhoneScreen(this.phoneCanvases[idx], spec, this.darkTheme);
      this.updateTexture(this.phoneTextures[idx], this.phoneCanvases[idx]);
    });
    paintDesktopScreen(this.desktopCanvas, this.desktopState, this.darkTheme);
    this.updateTexture(this.desktopTexture, this.desktopCanvas);

    paintHubMedallion(this.hubCanvas, this.seq, this.darkTheme);
    this.updateTexture(this.hubTexture, this.hubCanvas);

    paintPacketCard(this.packetCanvas, this.packet.label, this.packet.code);
    this.updateTexture(this.packetTexture, this.packetCanvas);
  }

  setDarkTheme(dark) {
    if (this.darkTheme === Boolean(dark)) return;
    this.darkTheme = Boolean(dark);
    this.refreshAllTextures();
    if (this.reducedMotion) this.renderFrame(performance.now());
  }

  setReducedMotion(reduced) {
    this.reducedMotion = Boolean(reduced);
    if (this.reducedMotion) this.renderFrame(performance.now());
  }

  setStage(stageFloat) {
    this.targetStage = clamp(stageFloat, 0, 3);
    if (this.reducedMotion) {
      this.currentStage = this.targetStage;
      this.renderFrame(performance.now());
    }
  }

  setPointer(nx, ny) {
    this.pointerX = clamp(nx, -1, 1);
    this.pointerY = clamp(ny, -1, 1);
  }

  triggerMessagePacket({ phoneIndex = 0, sender = "星云通行证", otp = "739104", preview = "5 分钟内有效", body = "" } = {}) {
    const idx = clamp(phoneIndex, 0, 2);
    this.seq += 1;
    const spec = this.phoneSpecs[idx];
    spec.senderName = sender;
    spec.senderInitial = [...sender.replace(/^[【\[]/, "")][0] || "信";
    spec.otp = otp;
    if (!otp) {
      spec.snippet1 = preview;
      spec.snippet2 = "刚刚捕获 · 已写入本机 outbox";
    }
    spec.seq = this.seq;
    spec.flash = 1;
    this.phoneTransforms[idx].kick = 1;

    this.packet = {
      phoneIndex: idx,
      progress: 0.001,
      active: true,
      label: otp ? "验证码汇入" : "新消息汇入",
      code: otp || `#${this.seq}`,
      pendingDesktopItem: {
        sender,
        initial: spec.senderInitial,
        color: spec.accent,
        otp,
        preview,
        body: body || `【${sender}】${otp ? `您的动态验证码为 ${otp}，` : ""}${preview}`,
        deviceBadge: `${spec.title.split(" ")[0]} · ${spec.simLine.slice(-4)}`,
      },
      landedHub: false,
    };

    if (!this.fallback2d) {
      paintPhoneScreen(this.phoneCanvases[idx], spec, this.darkTheme);
      this.updateTexture(this.phoneTextures[idx], this.phoneCanvases[idx]);
      paintPacketCard(this.packetCanvas, this.packet.label, this.packet.code);
      this.updateTexture(this.packetTexture, this.packetCanvas);
    }

    this.onTelemetry({
      phase: "capture",
      seq: this.seq,
      text: `${spec.title}捕获新消息 → 正在汇入私有中枢…`,
    });

    if (this.reducedMotion) {
      this.completePacketArrival();
      this.renderFrame(performance.now());
    }
  }

  completePacketArrival() {
    if (this.packet.pendingDesktopItem) {
      const nextItem = this.packet.pendingDesktopItem;
      this.desktopState.items = [
        nextItem,
        ...this.desktopState.items.filter((i) => i.sender !== nextItem.sender),
      ].slice(0, 4);
      this.desktopState.seq = this.seq;
      this.desktopState.flash = 1;
      this.packet.pendingDesktopItem = null;
      if (!this.fallback2d) {
        paintDesktopScreen(this.desktopCanvas, this.desktopState, this.darkTheme);
        this.updateTexture(this.desktopTexture, this.desktopCanvas);
        paintHubMedallion(this.hubCanvas, this.seq, this.darkTheme);
        this.updateTexture(this.hubTexture, this.hubCanvas);
      }
      this.onTelemetry({
        phase: "synced",
        seq: this.seq,
        text: `sync_clock #${this.seq} 已落库 · 桌面 Web/PWA 实时同步完成`,
      });
    }
  }

  resize() {
    const rect = this.canvas.getBoundingClientRect();
    const w = Math.max(300, Math.floor(rect.width));
    const h = Math.max(240, Math.floor(rect.height));
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const pw = Math.floor(w * dpr);
    const ph = Math.floor(h * dpr);
    if (this.canvas.width !== pw || this.canvas.height !== ph) {
      this.canvas.width = pw;
      this.canvas.height = ph;
    }
    this.aspect = pw / ph;
    if (this.gl) this.gl.viewport(0, 0, pw, ph);
    if (this.fallback2d) this.renderFallback2D();
  }

  setVisible(vis) {
    const next = Boolean(vis);
    if (this.visible === next) return;
    this.visible = next;
    if (next && !this.rafId) {
      this.lastTs = 0;
      this.start();
    }
  }

  start() {
    if (this.fallback2d || this.rafId) return;
    const loop = (ts) => {
      this.rafId = 0;
      if (!this.visible || document.visibilityState === "hidden") return;
      this.renderFrame(ts);
      if (!this.reducedMotion) {
        this.rafId = requestAnimationFrame(loop);
      }
    };
    this.rafId = requestAnimationFrame(loop);
  }

  drawMesh(mesh, uniforms) {
    const gl = this.gl;
    const L = this.locs;

    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.posBuf);
    gl.enableVertexAttribArray(L.aPos);
    gl.vertexAttribPointer(L.aPos, 3, gl.FLOAT, false, 0, 0);

    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.normBuf);
    gl.enableVertexAttribArray(L.aNormal);
    gl.vertexAttribPointer(L.aNormal, 3, gl.FLOAT, false, 0, 0);

    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.uvBuf);
    gl.enableVertexAttribArray(L.aUv);
    gl.vertexAttribPointer(L.aUv, 2, gl.FLOAT, false, 0, 0);

    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.idxBuf);

    gl.uniformMatrix4fv(L.uModel, false, uniforms.model);
    gl.uniform3fv(L.uBaseColor, uniforms.baseColor || [0.85, 0.86, 0.89]);
    gl.uniform3fv(L.uEmissive, uniforms.emissive || [0, 0, 0]);
    gl.uniform1f(L.uMetallic, uniforms.metallic ?? 0.65);
    gl.uniform1f(L.uRoughness, uniforms.roughness ?? 0.28);
    gl.uniform1f(L.uOpacity, uniforms.opacity ?? 1.0);
    gl.uniform1f(L.uUseTex, uniforms.tex ? 1.0 : 0.0);
    gl.uniform1f(L.uMode, uniforms.mode ?? 0.0);
    gl.uniform1f(L.uPacketT, uniforms.packetT ?? -1.0);

    if (uniforms.tex) {
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, uniforms.tex);
      gl.uniform1i(L.uTex, 0);
    }

    gl.drawElements(gl.TRIANGLES, mesh.count, gl.UNSIGNED_SHORT, 0);
  }

  renderFrame(ts) {
    if (this.fallback2d) {
      this.renderFallback2D();
      return;
    }
    const gl = this.gl;
    if (!gl) return;

    const dt = this.lastTs ? Math.min((ts - this.lastTs) / 1000, 0.05) : 0.016;
    this.lastTs = ts;
    if (!this.reducedMotion) this.time += dt;

    // Critically-damped spring for camera stage progression
    const stiffness = 86;
    const damping = 18.5;
    const force = (this.targetStage - this.currentStage) * stiffness - this.stageVel * damping;
    this.stageVel += force * dt;
    this.currentStage += this.stageVel * dt;

    // Smooth pointer parallax
    this.smoothPtrX = lerp(this.smoothPtrX, this.pointerX, Math.min(1, dt * 5.5));
    this.smoothPtrY = lerp(this.smoothPtrY, this.pointerY, Math.min(1, dt * 5.5));

    // Packet progression + ambient cycle every ~7.5s
    if (!this.reducedMotion) {
      if (this.packet.active) {
        this.packet.progress += dt * 0.72;
        if (this.packet.progress >= 1.0 && !this.packet.landedHub) {
          this.packet.landedHub = true;
          this.hubPulse = 0.01;
          paintHubMedallion(this.hubCanvas, this.seq, this.darkTheme);
          this.updateTexture(this.hubTexture, this.hubCanvas);
        }
        if (this.packet.progress >= 2.0) {
          this.packet.active = false;
          this.completePacketArrival();
          this.ambientCooldown = 4.6;
        }
      } else {
        this.ambientCooldown = (this.ambientCooldown ?? 3.5) - dt;
        if (this.ambientCooldown <= 0) {
          const nextIdx = (this.packet.phoneIndex + 1) % 3;
          const samples = [
            { sender: "星云通行证", otp: String(610200 + ((this.seq * 37) % 89000)), preview: "5 分钟内有效" },
            { sender: "青鸟驿站", otp: "", preview: `取件码 3-1-${2040 + (this.seq % 90)}` },
            { sender: "内网研发网关", otp: `${400 + (this.seq % 500)}-${710 + (this.seq % 240)}`, preview: "堡垒机动态码" },
          ];
          this.triggerMessagePacket({ phoneIndex: nextIdx, ...samples[nextIdx] });
        }
      }

      if (this.hubPulse > 0) {
        this.hubPulse += dt * 0.78;
        if (this.hubPulse >= 1) this.hubPulse = 0;
      }

      // Decay UI flash & physical phone kick
      this.phoneSpecs.forEach((s, i) => {
        if (s.flash > 0) {
          s.flash = Math.max(0, s.flash - dt * 1.4);
          if (s.flash === 0) {
            paintPhoneScreen(this.phoneCanvases[i], s, this.darkTheme);
            this.updateTexture(this.phoneTextures[i], this.phoneCanvases[i]);
          }
        }
        this.phoneTransforms[i].kick = Math.max(0, this.phoneTransforms[i].kick - dt * 2.6);
      });
      if (this.desktopState.flash > 0) {
        this.desktopState.flash = Math.max(0, this.desktopState.flash - dt * 1.3);
        if (this.desktopState.flash === 0) {
          paintDesktopScreen(this.desktopCanvas, this.desktopState, this.darkTheme);
          this.updateTexture(this.desktopTexture, this.desktopCanvas);
        }
      }
    }

    // Camera matrix
    const pose = sampleCameraPose(this.currentStage);
    const eye = [
      pose.eye[0] + this.smoothPtrX * 0.28,
      pose.eye[1] - this.smoothPtrY * 0.16,
      pose.eye[2],
    ];
    const proj = mat4Perspective((pose.fov * Math.PI) / 180, this.aspect || 1.5, 0.15, 28.0);
    const view = mat4LookAt(eye, pose.target);
    const viewProj = mat4Multiply(proj, view);

    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    gl.useProgram(this.program);
    gl.uniformMatrix4fv(this.locs.uViewProj, false, viewProj);
    gl.uniform3fv(this.locs.uCamPos, eye);
    gl.uniform1f(this.locs.uTime, this.time);
    gl.uniform1f(this.locs.uPulse, this.hubPulse);
    gl.uniform1f(this.locs.uDarkTheme, this.darkTheme ? 1.0 : 0.0);

    // 1. Studio Floor Shadow & Harmonic Ring Catcher
    gl.depthMask(false);
    this.drawMesh(this.meshes.floorQuad, {
      model: mat4Compose([0, -1.08, 0.1], [-Math.PI * 0.5, 0, 0]),
      mode: 2.0,
    });
    gl.depthMask(true);

    // 2. Private Self-Hosted Hub Monolith (Center)
    const hubHover = this.reducedMotion ? 0 : Math.sin(this.time * 1.5) * 0.018;
    const hubPos = [0.0, -0.50 + hubHover, 0.05];
    const chassisTint = this.darkTheme ? [0.24, 0.27, 0.33] : [0.84, 0.83, 0.80];
    this.drawMesh(this.meshes.hubChassis, {
      model: mat4Compose(hubPos, [0, 0.24, 0]),
      baseColor: chassisTint,
      metallic: 0.78,
      roughness: 0.22,
    });
    this.drawMesh(this.meshes.hubTopPlate, {
      model: mat4Compose([hubPos[0], hubPos[1] + 0.153, hubPos[2]], [-Math.PI * 0.5, 0.24, 0]),
      tex: this.hubTexture,
      metallic: 0.15,
      roughness: 0.2,
    });

    // 3. Desktop Web/PWA Studio Display (Upper Back)
    const deskTilt = -0.07;
    const deskCenter = [0.12, 0.86, -1.65];
    const aluminumTint = this.darkTheme ? [0.22, 0.25, 0.31] : [0.81, 0.84, 0.89];

    this.drawMesh(this.meshes.deskStandFoot, {
      model: mat4Compose([0.12, -1.03, -1.78], [0, 0, 0]),
      baseColor: aluminumTint,
      metallic: 0.76,
      roughness: 0.26,
    });
    this.drawMesh(this.meshes.deskStandNeck, {
      model: mat4Compose([0.12, -0.50, -1.86], [0.16, 0, 0]),
      baseColor: aluminumTint,
      metallic: 0.74,
      roughness: 0.28,
    });
    this.drawMesh(this.meshes.deskBody, {
      model: mat4Compose(deskCenter, [deskTilt, 0, 0]),
      baseColor: aluminumTint,
      metallic: 0.80,
      roughness: 0.22,
    });
    this.drawMesh(this.meshes.deskScreen, {
      model: mat4Compose([deskCenter[0], deskCenter[1], deskCenter[2] + 0.053], [deskTilt, 0, 0]),
      tex: this.desktopTexture,
      metallic: 0.08,
      roughness: 0.16,
    });

    // 4. Three Sculpted 3D Android Smartphones
    const phoneMetals = this.darkTheme
      ? [
          [0.24, 0.28, 0.36],
          [0.22, 0.29, 0.35],
          [0.31, 0.28, 0.24],
        ]
      : [
          [0.82, 0.85, 0.90], // Mist Silver-Blue
          [0.76, 0.83, 0.90], // Soft Fog Blue
          [0.87, 0.83, 0.77], // Warm Champagne Titanium
        ];

    this.phoneTransforms.forEach((tf, idx) => {
      const bob = this.reducedMotion ? 0 : Math.sin(this.time * 1.65 + idx * 1.9) * 0.045;
      const kickOffset = Math.sin(tf.kick * Math.PI) * 0.09;
      const pos = [tf.pos[0], tf.pos[1] + bob + kickOffset * 0.5, tf.pos[2] + kickOffset];
      const rot = [
        tf.rot[0] - kickOffset * 0.45 + this.smoothPtrY * 0.04,
        tf.rot[1] + this.smoothPtrX * 0.05,
        tf.rot[2],
      ];
      const bodyMat = mat4Compose(pos, rot);
      this.drawMesh(this.meshes.phoneBody, {
        model: bodyMat,
        baseColor: phoneMetals[idx],
        metallic: 0.76,
        roughness: 0.24,
      });

      // Offset screen slightly along phone's local +Z normal
      const screenOffset = [
        pos[0] + bodyMat[8] * 0.045,
        pos[1] + bodyMat[9] * 0.045,
        pos[2] + bodyMat[10] * 0.045,
      ];
      this.drawMesh(this.meshes.phoneScreen, {
        model: mat4Compose(screenOffset, rot),
        tex: this.phoneTextures[idx],
        metallic: 0.06,
        roughness: 0.14,
      });
    });

    // 5. Translucent Optical Light-Stream Conduits + Floating 3D Message Capsule
    gl.depthMask(false);
    const streamBase = this.darkTheme ? [0.26, 0.48, 0.84] : [0.30, 0.52, 0.86];
    const streamGlow = [0.90, 0.72, 0.42];

    this.meshes.phoneTubes.forEach((tube, idx) => {
      const isActive = this.packet.active && this.packet.phoneIndex === idx && this.packet.progress <= 1.0;
      this.drawMesh(tube, {
        model: mat4Identity(),
        mode: 1.0,
        baseColor: idx === 2 ? [0.68, 0.55, 0.36] : streamBase,
        emissive: streamGlow,
        opacity: isActive ? 0.95 : 0.55,
        packetT: isActive ? this.packet.progress : -1.0,
      });
    });

    const hubToDeskActive = this.packet.active && this.packet.progress > 1.0;
    this.drawMesh(this.meshes.hubDeskTube, {
      model: mat4Identity(),
      mode: 1.0,
      baseColor: streamBase,
      emissive: [0.95, 0.78, 0.48],
      opacity: hubToDeskActive ? 0.98 : 0.62,
      packetT: hubToDeskActive ? this.packet.progress - 1.0 : -1.0,
    });
    gl.depthMask(true);

    // 6. Gliding 3D Message Capsule along active Bezier path
    if (this.packet.active) {
      const leg1 = this.packet.progress <= 1.0;
      const t = leg1 ? smoothstep(0, 1, this.packet.progress) : smoothstep(0, 1, this.packet.progress - 1.0);
      const pts = leg1 ? this.phonePaths[this.packet.phoneIndex] : this.hubToDesktopPath;
      const p = bezierPoint(pts[0], pts[1], pts[2], pts[3], t);
      const scale = Math.sin(clamp(t, 0.04, 0.96) * Math.PI) * 0.45 + 0.55;
      const cardPos = [p[0], p[1] + 0.11, p[2]];
      const cardRot = [-0.15, this.smoothPtrX * 0.12, 0];

      this.drawMesh(this.meshes.packetCard, {
        model: mat4Compose(cardPos, cardRot, [scale, scale, scale]),
        baseColor: [0.96, 0.97, 1.0],
        emissive: [0.06, 0.10, 0.18],
        metallic: 0.25,
        roughness: 0.18,
      });
      this.drawMesh(this.meshes.packetFace, {
        model: mat4Compose([cardPos[0], cardPos[1], cardPos[2] + 0.015 * scale], cardRot, [scale, scale, scale]),
        tex: this.packetTexture,
        metallic: 0.05,
        roughness: 0.12,
      });
    }
  }

  // Graceful 2D Studio Fallback when WebGL is unavailable
  renderFallback2D() {
    const ctx = this.canvas.getContext("2d");
    if (!ctx) return;
    const W = this.canvas.width, H = this.canvas.height;
    ctx.clearRect(0, 0, W, H);
    const grad = ctx.createRadialGradient(W * 0.5, H * 0.46, 20, W * 0.5, H * 0.5, W * 0.52);
    grad.addColorStop(0, this.darkTheme ? "rgba(56, 98, 168, 0.25)" : "rgba(72, 124, 202, 0.16)");
    grad.addColorStop(1, "rgba(0, 0, 0, 0)");
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, W, H);

    // Optical curves
    const hubX = W * 0.5, hubY = H * 0.58;
    const deskX = W * 0.5, deskY = H * 0.24;
    const phones = [
      [W * 0.20, H * 0.72, "Pixel 9 · 0412"],
      [W * 0.42, H * 0.80, "Xiaomi 14 · 9105"],
      [W * 0.80, H * 0.72, "Galaxy S24 · 5520"],
    ];
    ctx.strokeStyle = "#3c6fb8";
    ctx.lineWidth = 3;
    phones.forEach(([px, py]) => {
      ctx.beginPath();
      ctx.moveTo(px, py);
      ctx.quadraticCurveTo((px + hubX) * 0.5, py - H * 0.18, hubX, hubY);
      ctx.stroke();
    });
    ctx.beginPath();
    ctx.moveTo(hubX, hubY);
    ctx.lineTo(deskX, deskY);
    ctx.stroke();

    roundRect(ctx, deskX - 150, deskY - 68, 300, 120, 18);
    ctx.fillStyle = this.darkTheme ? "#1d2330" : "#ffffff";
    ctx.fill();
    ctx.fillStyle = this.darkTheme ? "#edf1f8" : "#18202c";
    ctx.font = "600 18px -apple-system, 'PingFang SC', sans-serif";
    ctx.fillText("鸿枢 Web / PWA 桌面收件箱", deskX - 118, deskY - 12);
    ctx.fillStyle = "#2f9e5b";
    ctx.fillText(`sync_clock #${this.seq} · 实时已就绪`, deskX - 118, deskY + 22);
  }
}
