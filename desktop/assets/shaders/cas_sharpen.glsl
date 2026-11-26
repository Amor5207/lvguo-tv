//!DESC Lvguo FidelityFX CAS Contrast Adaptive Sharpening
//!HOOK MAIN
//!BIND HOOKED

vec4 hook() {
    float sharpness = 0.85;

    vec4 center = HOOKED_tex(HOOKED_pos);
    vec3 a = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0, -1.0)).rgb;
    vec3 b = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 0.0, -1.0)).rgb;
    vec3 c = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0, -1.0)).rgb;
    vec3 d = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0,  0.0)).rgb;
    vec3 e = center.rgb;
    vec3 f = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0,  0.0)).rgb;
    vec3 g = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2(-1.0,  1.0)).rgb;
    vec3 h = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 0.0,  1.0)).rgb;
    vec3 i = HOOKED_tex(HOOKED_pos + HOOKED_pt * vec2( 1.0,  1.0)).rgb;

    vec3 mn = min(min(min(d, e), min(f, b)), h);
    vec3 mn2 = min(mn, min(min(a, c), min(g, i)));
    mn = (mn + mn2) * 0.5;

    vec3 mx = max(max(max(d, e), max(f, b)), h);
    vec3 mx2 = max(mx, max(max(a, c), min(g, i)));
    mx2 = max(mx2, max(g, i));
    mx = (mx + mx2) * 0.5;

    vec3 amp = clamp(min(mn, 2.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0);
    amp = sqrt(amp);

    float peak = -1.0 / mix(8.0, 4.5, sharpness);
    vec3 w = amp * peak;
    vec3 rcpW = 1.0 / (1.0 + 4.0 * w);

    vec3 outColor = clamp(((b + d + f + h) * w + e) * rcpW, 0.0, 1.0);
    return vec4(outColor, center.a);
}
