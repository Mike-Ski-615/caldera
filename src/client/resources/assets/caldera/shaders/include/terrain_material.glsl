float calderaMaterialAlphaCutoff(uint material) {
    const float cutoff[4] = float[4](0.0, 0.1, 0.5, 1.0);
    return cutoff[(material >> 1u) & 3u];
}

vec4 calderaSampleTerrainMaterial(sampler2D atlas, vec2 uv, uint material) {
    
    vec2 dx = dFdx(uv), dy = dFdy(uv);
    return (material & 1u) != 0u ? textureGrad(atlas, uv, dx, dy) : textureLod(atlas, uv, 0.0);
}
