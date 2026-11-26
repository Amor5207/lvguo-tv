//!DESC Lvguo Super-Resolution Pass 1: Edge Reconstruction
//!HOOK MAIN
//!BIND HOOKED

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec4 hook() {
    vec4 center = HOOKED_tex(HOOKED_pos);
    vec3 e = center.rgb;

    vec3 n  = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 0.0, -1.0)).rgb;
    vec3 s  = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 0.0,  1.0)).rgb;
    vec3 w  = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0,  0.0)).rgb;
    vec3 ea = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0,  0.0)).rgb;
    vec3 nw = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0, -1.0)).rgb;
    vec3 ne = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0, -1.0)).rgb;
    vec3 sw = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0,  1.0)).rgb;
    vec3 se = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0,  1.0)).rgb;

    float ln  = luma(n);
    float ls  = luma(s);
    float lw  = luma(w);
    float lea = luma(ea);
    float lnw = luma(nw);
    float lne = luma(ne);
    float lsw = luma(sw);
    float lse = luma(se);
    float le  = luma(e);

    // Gradient magnitude & dominant edge orientation (H/V + diagonals)
    float gx = abs(lea - lw) * 0.5 + abs(lne - lsw) * 0.25 + abs(lse - lnw) * 0.25;
    float gy = abs(ls - ln) * 0.5 + abs(lse - lnw) * 0.25 + abs(lsw - lne) * 0.25;
    float edgeStrength = clamp((gx + gy) * 3.0, 0.0, 1.0);

    // Unsharp high-frequency synthesis focused along orthogonal-to-edge axis
    vec3 orthoAvg = (gx > gy) ? (w + ea) * 0.5 : (n + s) * 0.5;
    vec3 crossAvg = (n + s + w + ea) * 0.25;
    vec3 diagAvg  = (nw + ne + sw + se) * 0.25;
    vec3 localAvg = mix(crossAvg * 0.65 + diagAvg * 0.35, orthoAvg, 0.35 * edgeStrength);

    vec3 highFreq = e - localAvg;
    float hfLuma = abs(le - luma(localAvg));
    // Boost fine details more aggressively than flat areas while suppressing compression noise
    float noiseGate = smoothstep(0.012, 0.06, hfLuma);
    float boost = mix(0.65, 1.45, edgeStrength) * noiseGate;

    vec3 mn = min(min(min(w, ea), min(n, s)), min(min(nw, ne), min(sw, se)));
    mn = min(mn, e);
    vec3 mx = max(max(max(w, ea), max(n, s)), max(max(nw, ne), max(sw, se)));
    mx = max(mx, e);

    vec3 recon = e + highFreq * boost;
    // Allow controlled overshoot beyond local min/max so edges reconstruct crisply
    vec3 margin = (mx - mn) * 0.22 + vec3(0.02);
    vec3 outColor = clamp(recon, max(vec3(0.0), mn - margin), min(vec3(1.0), mx + margin));
    return vec4(outColor, center.a);
}

//!DESC Lvguo Super-Resolution Pass 2: RCAS + Perceptual Clarity
//!HOOK MAIN
//!BIND HOOKED

float luma2(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec4 hook() {
    vec4 center = HOOKED_tex(HOOKED_pos);
    vec3 e = center.rgb;
    vec3 n = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 0.0, -1.0)).rgb;
    vec3 s = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 0.0,  1.0)).rgb;
    vec3 w = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0,  0.0)).rgb;
    vec3 ea = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0,  0.0)).rgb;

    float bL = luma2(n);
    float dL = luma2(w);
    float eL = luma2(e);
    float fL = luma2(ea);
    float hL = luma2(s);

    // FidelityFX RCAS neighborhood lobe calculation
    float mnL = min(min(bL, dL), min(fL, hL));
    float mxL = max(max(bL, dL), max(fL, hL));
    float hitMinL = min(mnL, eL) / max(4.0 * mxL, 1e-4);
    float hitMaxL = (1.0 - max(mxL, eL)) / max(4.0 * mnL - 4.0, -1e-4);
    float lobeL = max(-hitMinL, hitMaxL);

    // RCAS sharpness (0.0 = max, negative exponent attenuates)
    float rcasSharp = 0.88;
    float lobe = max(-0.1875, min(lobeL, 0.0)) * rcasSharp;

    vec3 outColor = (e + lobe * (n + s + w + ea)) / (1.0 + 4.0 * lobe);
    outColor = clamp(outColor, 0.0, 1.0);

    // Perceptual micro-contrast & slight vibrancy lift
    vec3 sCurve = outColor * outColor * (3.0 - 2.0 * outColor);
    outColor = mix(outColor, sCurve, 0.14);

    float avgL = luma2(outColor);
    outColor = clamp(mix(vec3(avgL), outColor, 1.06), 0.0, 1.0);
    return vec4(outColor, center.a);
}
