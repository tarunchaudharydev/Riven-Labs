import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  experimental: { useTypeScriptCli: false },
  cacheComponents: true,
  partialPrefetching: true,
};

export default nextConfig;
