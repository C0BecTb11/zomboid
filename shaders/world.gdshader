shader_type spatial;
render_mode cull_back, diffuse_lambert, specular_disabled;

// Видимость (туман войны) — текстура 100×100 по карте
uniform sampler2D vis_tex : filter_linear, repeat_disable;
uniform vec3 player_pos = vec3(50.0, 0.0, 50.0);
uniform vec2 cam_back = vec2(0.0, 1.0);
uniform float cut = 1.0;
uniform float night = 0.0;
uniform float map_size = 100.0;

varying vec3 wpos;
varying float kind;

vec3 to_linear(vec3 c) {
	return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

void vertex() {
	wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	kind = COLOR.a;
}

void fragment() {
	// стены между камерой и игроком прорезаются
	// только для камеры игрока (перспектива); в проходе теней солнца (ортогональная проекция) стены целые —
	// иначе тень зависела от поворота камеры и «срезанная» стена давала тень без крыши
	bool persp = PROJECTION_MATRIX[3][3] < 0.5;
	if (persp && kind < 0.25 && cut > 0.5 && wpos.y > 0.55) {
		vec2 d = wpos.xz - player_pos.xz;
		float along = dot(d, cam_back);
		float side = abs(d.x * cam_back.y - d.y * cam_back.x);
		if (along > 0.2 && along < 7.0 && side < 2.2 + along * 0.25) discard;
	}
	vec3 c = to_linear(COLOR.rgb);
	float v = texture(vis_tex, wpos.xz / map_size).r;
	if (wpos.y > 2.35) v = max(v, 0.8);
	c *= mix(0.42 - 0.2 * night, 1.0, v);
	ALBEDO = c;
	ROUGHNESS = 0.95;
	if (kind > 0.4 && kind < 0.6) {
		EMISSION = c * (0.5 + night * 2.0);
	}
}
