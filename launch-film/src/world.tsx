import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import { useThree } from "@react-three/fiber";
import {
  continueRender,
  delayRender,
  cancelRender,
  staticFile,
} from "remotion";
import * as T from "three";
export type V3 = [number, number, number];
export const smooth = (t: number) => {
  const v = Math.max(0, Math.min(1, t));
  return v * v * (3 - 2 * v);
};
export const mix = (a: number, b: number, t: number) => a + (b - a) * t;
export const lerp3 = (a: V3, b: V3, t: number): V3 => [
  mix(a[0], b[0], t),
  mix(a[1], b[1], t),
  mix(a[2], b[2], t),
];
export const Camera = ({
  position,
  target,
  fov = 40,
}: {
  position: V3;
  target: V3;
  fov?: number;
}) => {
  const { camera } = useThree();
  useLayoutEffect(() => {
    camera.position.set(...position);
    camera.lookAt(...target);
    if (camera instanceof T.PerspectiveCamera) {
      camera.fov = fov;
      camera.updateProjectionMatrix();
    }
    camera.updateMatrixWorld();
  }, [camera, position, target, fov]);
  return null;
};
// Wait for the exact Kotlin Compose frame, then synchronously repaint ThreeCanvas.
const LoadedScreen = ({
  path,
  w,
  h,
}: {
  path: string;
  w: number;
  h: number;
}) => {
  const [handle] = useState(() => delayRender(`Kotlin UI: ${path}`));
  const [texture, setTexture] = useState<T.Texture | null>(null);
  const advance = useThree((s) => s.advance);
  useEffect(() => {
    let alive = true;
    let loaded: T.Texture | undefined;
    new T.TextureLoader().load(
      staticFile(path),
      (value) => {
        loaded = value;
        value.colorSpace = T.SRGBColorSpace;
        value.anisotropy = 8;
        value.minFilter = T.LinearFilter;
        value.magFilter = T.LinearFilter;
        if (alive) setTexture(value);
        else value.dispose();
      },
      undefined,
      (e) => cancelRender(e),
    );
    return () => {
      alive = false;
      loaded?.dispose();
      continueRender(handle);
    };
  }, [handle, path]);
  useLayoutEffect(() => {
    if (texture) {
      const raf = requestAnimationFrame(() => {
        advance(performance.now());
        continueRender(handle);
      });
      return () => cancelAnimationFrame(raf);
    }
  }, [texture, advance, handle]);
  // Mount with a texture already present so Three compiles USE_MAP on first draw.
  // Changing an existing material from map=null leaves that shader flag unset.
  if (!texture) return null;
  return (
    <mesh>
      <planeGeometry args={[w, h]} />
      <meshBasicMaterial
        key={texture.uuid}
        map={texture}
        color="white"
        toneMapped={false}
      />
    </mesh>
  );
};
export const NativeScreen = ({
  device,
  frame,
  w,
  h,
  still = false,
}: {
  device: "tv" | "mobile" | "mobile-details";
  frame: number;
  w: number;
  h: number;
  still?: boolean;
}) => {
  const file = still
    ? `screens/${device}-home.png`
    : `screens/${device === "mobile-details" ? device : device + "-home"}/${String(Math.max(0, Math.min(59, Math.floor(frame / 2)))).padStart(5, "0")}.png`;
  return <LoadedScreen key={file} path={file} w={w} h={h} />;
};
export const Lights = ({ warm = false }: { warm?: boolean }) => (
  <>
    <ambientLight intensity={0.48} />
    <hemisphereLight args={["#ccecff", "#12151f", 0.65]} />
    <directionalLight
      position={[-3, 5, 4]}
      intensity={2.5}
      color={warm ? "#ffe1bc" : "#ecf7ff"}
      castShadow
      shadow-mapSize={[2048, 2048]}
      shadow-camera-left={-5}
      shadow-camera-right={5}
      shadow-camera-top={5}
      shadow-camera-bottom={-5}
      shadow-bias={-0.0002}
    />
    <pointLight
      position={[3, 2, -1]}
      intensity={18}
      distance={10}
      color="#329ddd"
    />
    <pointLight
      position={[-3, 2, -2]}
      intensity={12}
      distance={9}
      color="#e7aa6c"
    />
    <rectAreaLight
      position={[0, 4, 3]}
      width={5}
      height={3}
      intensity={3}
      color="#edf9ff"
    />
  </>
);
export const Block = ({
  size,
  position = [0, 0, 0],
  color = "#171e26",
  radius = 0.025,
  metalness = 0.3,
  roughness = 0.35,
}: {
  size: V3;
  position?: V3;
  color?: string;
  radius?: number;
  metalness?: number;
  roughness?: number;
}) => {
  const geometry = useMemo(() => {
    const [w, h, d] = size,
      r = Math.min(radius, w / 2, h / 2),
      x = -w / 2,
      y = -h / 2;
    const shape = new T.Shape();
    shape.moveTo(x + r, y);
    shape.lineTo(x + w - r, y);
    shape.quadraticCurveTo(x + w, y, x + w, y + r);
    shape.lineTo(x + w, y + h - r);
    shape.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    shape.lineTo(x + r, y + h);
    shape.quadraticCurveTo(x, y + h, x, y + h - r);
    shape.lineTo(x, y + r);
    shape.quadraticCurveTo(x, y, x + r, y);
    const geo = new T.ExtrudeGeometry(shape, {
      depth: d,
      bevelEnabled: false,
      curveSegments: 5,
      steps: 1,
    });
    geo.translate(0, 0, -d / 2);
    return geo;
  }, [size[0], size[1], size[2], radius]);
  useEffect(() => () => geometry.dispose(), [geometry]);
  return (
    <mesh geometry={geometry} position={position} castShadow receiveShadow>
      <meshStandardMaterial
        color={color}
        metalness={metalness}
        roughness={roughness}
      />
    </mesh>
  );
};
export const Limb = ({
  a,
  b,
  r = 0.04,
  color = "#915d40",
  endRadius,
}: {
  a: V3;
  b: V3;
  r?: number;
  color?: string;
  endRadius?: number;
}) => {
  const av = new T.Vector3(...a),
    bv = new T.Vector3(...b),
    delta = bv.clone().sub(av),
    q = new T.Quaternion().setFromUnitVectors(
      new T.Vector3(0, 1, 0),
      delta.clone().normalize(),
    );
  return (
    <mesh position={av.add(bv).multiplyScalar(0.5)} quaternion={q} castShadow>
      <cylinderGeometry args={[endRadius ?? r, r, delta.length(), 14]} />
      <meshStandardMaterial color={color} roughness={0.65} />
    </mesh>
  );
};
export const Oval = ({
  position,
  scale,
  color,
  roughness = 0.55,
}: {
  position: V3;
  scale: V3;
  color: string;
  roughness?: number;
}) => (
  <mesh position={position} scale={scale} castShadow>
    <sphereGeometry args={[1, 24, 20]} />
    <meshStandardMaterial color={color} roughness={roughness} />
  </mesh>
);
// Articulated palms, thumbs, knuckles and curled finger joints are genuine meshes.
export const Hand = ({
  curl = 0.5,
  side = 1,
}: {
  curl?: number;
  side?: number;
}) => (
  <group scale={[side, 1, 1]}>
    <Oval position={[0, 0, 0]} scale={[0.075, 0.105, 0.028]} color="#946746" />
    {[0, 1, 2, 3].map((i) => {
      const x = (i - 1.5) * 0.036,
        length = [0.087, 0.103, 0.096, 0.077][i];
      const a: V3 = [x, 0.07, 0.003],
        b: V3 = [x, 0.07 + length * 0.56, -0.015 * curl],
        c: V3 = [x, 0.07 + length * (1 - 0.3 * curl), 0.045 * curl];
      return (
        <group key={i}>
          <Limb a={a} b={b} r={0.015} endRadius={0.013} />
          <Oval position={b} scale={[0.015, 0.018, 0.016]} color="#946746" />
          <Limb a={b} b={c} r={0.013} endRadius={0.01} />
          <Oval position={c} scale={[0.011, 0.017, 0.011]} color="#946746" />
        </group>
      );
    })}
    <Limb
      a={[-0.055, -0.012, 0]}
      b={[-0.096, 0.037, 0.025]}
      r={0.023}
      endRadius={0.018}
    />
    <Limb
      a={[-0.096, 0.037, 0.025]}
      b={[-0.068, 0.074, 0.053 * curl]}
      r={0.018}
      endRadius={0.014}
    />
  </group>
);
export const Person = ({
  place,
  release,
  walk,
}: {
  place: number;
  release: number;
  walk: number;
}) => {
  const sway = Math.sin(walk * 17) * 0.035,
    y = 1.5 - 0.025 * Math.sin(release * Math.PI),
    gripY = 1.18 + 0.5 * (1 - place);
  const handL = lerp3([-0.99, gripY, 0.385], [-0.34, 1.04, -0.4], release),
    handR = lerp3([0.99, gripY, 0.385], [0.34, 1.04, -0.4], release);
  return (
    <group
      position={[walk * 3.6, sway, -0.33 - walk * 0.3]}
      rotation={[0, walk * 0.9, 0]}
    >
      <Oval
        position={[0, 1.32, -0.12]}
        scale={[0.265, 0.38, 0.16]}
        color="#242b32"
      />
      <Oval
        position={[0, 1.58, -0.09]}
        scale={[0.29, 0.16, 0.15]}
        color="#242b32"
      />
      <Limb a={[0, 1.63, -0.1]} b={[0, 1.72, -0.1]} r={0.064} />
      <Oval
        position={[0, 1.82, -0.1]}
        scale={[0.102, 0.14, 0.1]}
        color="#946746"
      />
      <Oval
        position={[0, 1.89, -0.13]}
        scale={[0.106, 0.081, 0.095]}
        color="#131715"
      />
      <Oval
        position={[0, 1.805, -0.006]}
        scale={[0.028, 0.031, 0.037]}
        color="#946746"
      />
      {[-1, 1].map((side) => {
        const hand = side < 0 ? handL : handR,
          shoulder: V3 = [side * 0.26, y, -0.1],
          elbow: V3 = [
            side * mix(0.6, 0.3, release),
            mix(gripY + 0.13, 1.22, release),
            -0.13,
          ],
          hip: V3 = [side * 0.12, 0.94, -0.14],
          phase =
            Math.sin(walk * 17 + (side * Math.PI) / 2) *
            0.2 *
            Math.min(1, walk * 10),
          knee: V3 = [side * 0.13, 0.53, -0.12 + phase],
          ankle: V3 = [side * 0.15, 0.09, -0.13 - phase];
        return (
          <group key={side}>
            <Limb
              a={shoulder}
              b={elbow}
              r={0.078}
              endRadius={0.06}
              color="#242b32"
            />
            <Oval
              position={elbow}
              scale={[0.065, 0.065, 0.065]}
              color="#242b32"
            />
            <Limb a={elbow} b={hand} r={0.048} endRadius={0.032} />
            <group
              position={hand}
              rotation={[0, (side * Math.PI) / 2, -side * 0.15]}
            >
              <Hand curl={1 - release * 0.65} side={side} />
            </group>
            <Limb a={hip} b={knee} r={0.085} endRadius={0.07} color="#19202a" />
            <Limb
              a={knee}
              b={ankle}
              r={0.06}
              endRadius={0.044}
              color="#19202a"
            />
            <Block
              size={[0.14, 0.08, 0.26]}
              position={[ankle[0], 0.045, ankle[2] + 0.045]}
              color="#11171e"
            />
          </group>
        );
      })}
    </group>
  );
};
export const BrandMark = ({ scale = 1 }: { scale?: number }) => {
  const tri = useMemo(
    () =>
      new T.Shape([
        new T.Vector2(-0.03, -0.055),
        new T.Vector2(-0.03, 0.055),
        new T.Vector2(0.07, 0),
      ]),
    [],
  );
  return (
    <group scale={scale}>
      <Block
        size={[0.27, 0.27, 0.008]}
        radius={0.045}
        color="#1761b0"
        roughness={0.25}
      />
      <mesh position={[0, 0, 0.008]}>
        <shapeGeometry args={[tri]} />
        <meshBasicMaterial color="white" toneMapped={false} />
      </mesh>
      <mesh position={[0.086, 0.086, 0.009]}>
        <circleGeometry args={[0.013, 24]} />
        <meshBasicMaterial color="#82d7ff" toneMapped={false} />
      </mesh>
    </group>
  );
};
export const Television = ({
  on = false,
  frame = 0,
  still = false,
}: {
  on?: boolean;
  frame?: number;
  still?: boolean;
}) => (
  <group>
    <Block
      size={[2.17, 1.25, 0.06]}
      color="#0b1017"
      radius={0.017}
      metalness={0.85}
      roughness={0.22}
    />
    <Block
      size={[2.14, 1.22, 0.062]}
      color="#343e4b"
      radius={0.011}
      metalness={0.9}
    />
    <group position={[0, 0, 0.033]}>
      {on ? (
        <NativeScreen
          device="tv"
          frame={frame}
          w={2.105}
          h={1.184}
          still={still}
        />
      ) : (
        <>
          <mesh>
            <planeGeometry args={[2.105, 1.184]} />
            <meshPhysicalMaterial
              color="#03080c"
              metalness={0.5}
              roughness={0.15}
              clearcoat={1}
            />
          </mesh>
          <group position={[0, 0, 0.002]}>
            <BrandMark scale={0.7} />
          </group>
        </>
      )}
    </group>
    {[-1, 1].map((side) => (
      <group key={side}>
        <Limb
          a={[side * 0.7, -0.59, 0]}
          b={[side * 0.83, -0.73, 0.12]}
          r={0.017}
          color="#161c24"
        />
        <Limb
          a={[side * 0.7, -0.59, 0]}
          b={[side * 0.64, -0.73, -0.16]}
          r={0.017}
          color="#161c24"
        />
      </group>
    ))}
    <mesh position={[0, -0.608, 0.034]}>
      <sphereGeometry args={[0.004, 8, 8]} />
      <meshBasicMaterial color={on ? "#82d7ff" : "#737f8c"} />
    </mesh>
  </group>
);
export const Remote = ({ click = 0 }: { click?: number }) => (
  <group>
    <Block
      size={[0.24, 0.85, 0.05]}
      radius={0.06}
      metalness={0.65}
      roughness={0.22}
    />
    <Block
      size={[0.23, 0.83, 0.052]}
      radius={0.054}
      color="#242e38"
      metalness={0.6}
    />
    <mesh position={[0, 0.12, 0.029]} rotation={[Math.PI / 2, 0, 0]}>
      <cylinderGeometry args={[0.073, 0.073, 0.008, 48]} />
      <meshStandardMaterial color="#0a1119" roughness={0.33} metalness={0.3} />
    </mesh>
    <mesh position={[0, 0.12, 0.037 - click * 0.008]}>
      <circleGeometry args={[0.04, 32]} />
      <meshStandardMaterial color="#8bcce7" metalness={0.7} roughness={0.25} />
    </mesh>
    <mesh position={[-0.055, 0.3, 0.032]}>
      <sphereGeometry args={[0.023, 20, 16]} />
      <meshStandardMaterial color="#b0bdc6" metalness={0.75} roughness={0.25} />
    </mesh>
    {[0, 1, 2].map((y) =>
      [-1, 1].map((x) => (
        <Block
          key={`${x}${y}`}
          size={[0.065, 0.033, 0.013]}
          position={[x * 0.05, -0.08 - y * 0.085, 0.028]}
          radius={0.01}
          color={y === 2 ? "#1761b0" : "#4f5b66"}
        />
      )),
    )}
    <group position={[0, -0.33, 0.032]}>
      <BrandMark scale={0.15} />
    </group>
  </group>
);
export const Phone = ({
  on = false,
  frame = 0,
  still = false,
  details = false,
}: {
  on?: boolean;
  frame?: number;
  still?: boolean;
  details?: boolean;
}) => (
  <group>
    <Block
      size={[0.44, 0.918, 0.043]}
      radius={0.057}
      color="#aebbc6"
      metalness={0.88}
      roughness={0.21}
    />
    <Block
      size={[0.43, 0.906, 0.046]}
      radius={0.05}
      color="#080d14"
      metalness={0.25}
      roughness={0.3}
    />
    <group position={[0, 0, 0.026]}>
      {on ? (
        <NativeScreen
          device={details ? "mobile-details" : "mobile"}
          frame={frame}
          w={0.401}
          h={0.872}
          still={still}
        />
      ) : (
        <>
          <mesh>
            <planeGeometry args={[0.401, 0.872]} />
            <meshBasicMaterial color="#040b13" />
          </mesh>
          <group position={[0, 0, 0.001]}>
            <BrandMark scale={0.66} />
          </group>
        </>
      )}
    </group>
    <Block
      size={[0.08, 0.016, 0.003]}
      position={[0, 0.433, 0.029]}
      radius={0.007}
      color="#04060a"
    />
    <Block
      size={[0.004, 0.088, 0.012]}
      position={[0.222, 0.19, 0]}
      radius={0.001}
      color="#c2d1df"
    />
    <Block
      size={[0.004, 0.06, 0.012]}
      position={[-0.222, 0.2, 0]}
      radius={0.001}
      color="#c2d1df"
    />
    <Block
      size={[0.11, 0.026, 0.004]}
      position={[0, -0.419, 0.029]}
      radius={0.012}
      color="#ecf4fa"
    />
  </group>
);
export const Floor = () => (
  <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -0.005, 0]} receiveShadow>
    <planeGeometry args={[50, 50]} />
    <meshStandardMaterial color="#101722" metalness={0.45} roughness={0.3} />
  </mesh>
);
export const Room = () => (
  <>
    <Floor />
    <Block
      size={[12, 5, 0.12]}
      position={[0, 2.4, -2.2]}
      color="#181f28"
      metalness={0.12}
      roughness={0.85}
    />
    {Array.from({ length: 15 }, (_, i) => (
      <Block
        key={i}
        size={[0.045, 3, 0.06]}
        position={[2.15 + i * 0.12, 1.65, -2.1]}
        color="#4d3f35"
        metalness={0.15}
        roughness={0.7}
      />
    ))}
    <Block
      size={[2.68, 0.105, 0.68]}
      position={[0, 0.545, -0.06]}
      color="#715340"
      metalness={0.1}
      roughness={0.55}
    />
    <Block
      size={[2.61, 0.36, 0.62]}
      position={[0, 0.326, -0.06]}
      color="#3e302b"
      metalness={0.05}
      roughness={0.67}
    />
    {[-1, 1].map((x) => (
      <Block
        key={x}
        size={[0.04, 0.1, 0.04]}
        position={[x * 1.17, 0.08, -0.04]}
        color="#b89468"
        metalness={0.8}
      />
    ))}
    {[-1, 0, 1].map((x) => (
      <Block
        key={x}
        size={[0.81, 0.28, 0.006]}
        position={[x * 0.854, 0.32, 0.255]}
        color="#655043"
        radius={0.004}
        metalness={0.05}
        roughness={0.63}
      />
    ))}
    <mesh position={[0, 0.16, 0.27]}>
      <planeGeometry args={[2.4, 0.014]} />
      <meshBasicMaterial color="#936e3d" />
    </mesh>
    <Block
      size={[0.018, 2.1, 0.018]}
      position={[-2.03, 1.05, -0.55]}
      color="#bba27f"
      metalness={0.8}
    />
    <mesh position={[-2.03, 2.1, -0.55]}>
      <coneGeometry args={[0.26, 0.34, 32, 1, true]} />
      <meshStandardMaterial
        color="#d4c7b6"
        side={T.DoubleSide}
        roughness={0.65}
      />
    </mesh>
    <pointLight
      position={[-2.03, 1.96, -0.55]}
      intensity={2}
      distance={3}
      color="#ffc58c"
    />
    <Block
      size={[1.3, 0.4, 0.84]}
      position={[1.85, 0.2, 1.3]}
      color="#263342"
      radius={0.12}
      roughness={0.9}
    />
    <Block
      size={[1.28, 0.38, 0.16]}
      position={[1.85, 0.5, 1.66]}
      color="#263342"
      radius={0.065}
      roughness={0.9}
    />
  </>
);
