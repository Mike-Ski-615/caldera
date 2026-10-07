vec4 caldera_sampleNearestAtlasTexel(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
	vec2 uvTexelCoords = uv / pixelSize;
	vec2 texelCenter = round(uvTexelCoords) - 0.5;
	vec2 texelOffset = uvTexelCoords - texelCenter;

	texelOffset = (texelOffset - 0.5) * pixelSize / max(texelScreenSize, vec2(1.0e-6)) + 0.5;
	texelOffset = clamp(texelOffset, 0.0, 1.0);

	vec2 snappedUv = (texelCenter + texelOffset) * pixelSize;
	return textureGrad(source, snappedUv, du, dv);
}

vec4 caldera_sampleTerrainAtlas(sampler2D source, vec2 uv, vec2 pixelSize) {
	vec2 du = dFdx(uv);
	vec2 dv = dFdy(uv);
	vec2 texelScreenSize = sqrt(du * du + dv * dv);
	return caldera_sampleNearestAtlasTexel(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 caldera_sampleTerrainAtlasBaseLevel(sampler2D source, vec2 uv, vec2 pixelSize) {
	vec2 uvTexelCoords = uv / pixelSize;
	vec2 texelCenter = round(uvTexelCoords) - 0.5;
	vec2 texelOffset = clamp(uvTexelCoords - texelCenter, 0.0, 1.0);
	return textureLod(source, (texelCenter + texelOffset) * pixelSize, 0.0);
}
