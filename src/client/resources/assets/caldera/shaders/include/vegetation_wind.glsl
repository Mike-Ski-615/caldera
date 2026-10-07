layout(std140) uniform CalderaWind { vec4 WindOriginTime; vec4 WindStrength; };
vec3 calderaWindOffset(vec3 relative, uint packedMaterial) {
    uint material=packedMaterial & 65535u;
    if(material<2u || material>5u) return vec3(0);
    float weight=float(packedMaterial >> 16u)/65535.0;
    vec3 p=relative+WindOriginTime.xyz;
    float t=WindOriginTime.w;
    
    float phase=dot(p.xz,vec2(37.0,23.0))*(6.28318530718/4096.0);
    float gust=0.65+0.35*sin(t*0.47+phase*2.0);
    float sway=sin(t*1.1+phase)*gust;
    float flutter=sin(t*2.3+phase*5.0+p.y*(228.0*6.28318530718/4096.0));
    float amplitude=material==5u?0.055:0.16;
    return vec3(sway,material==5u?flutter*0.22:0.0,sway*0.45+flutter*0.2)
        *amplitude*weight*WindStrength.x;
}
