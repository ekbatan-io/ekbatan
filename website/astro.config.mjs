// @ts-check
import { defineConfig } from "astro/config";
import mdx from "@astrojs/mdx";
import { unified } from "@astrojs/markdown-remark";
import remarkBasePath from "./src/plugins/remark-base-path.mjs";

// Astro config for the Ekbatan website.
//
// The visual identity (brutalist palette, Space Grotesk + DM Sans + JetBrains Mono,
// brut-borders/brut-shadows) was carried over verbatim from the original
// design — the Tailwind-compiled CSS bundle and the woff2 fonts ship as static
// assets (`src/styles/*.css` + `public/fonts/*.woff2`) and Astro just wires them
// into each page via the BaseLayout. We are NOT rebuilding Tailwind from a config —
// the existing styles.css is treated as a frozen design-system bundle.
//
// MDX is enabled for /concepts/, /learn/, and /reference/ — those sections are
// content-heavy, mostly migrated from docs/*.md, and benefit from being able to
// drop in interactive components (notably <StackPicker />) inline.

// Deployment base path. Default is "/" (root of a subdomain — Cloudflare Pages,
// Vercel, custom domain). Override with the BASE env var when deploying to a
// subpath, e.g. `BASE=/ekbatan/ npm run build` for a GitHub Pages project site.
// `remarkBasePath` then prefixes absolute markdown/MDX links so source stays
// portable and only this one knob switches deployments.
const BASE = process.env.BASE || "/";

export default defineConfig({
    base: BASE,
    integrations: [mdx()],
    markdown: {
        shikiConfig: {
            theme: "github-light",
        },
        // Astro 7's default Markdown processor (Satteri) runs no remark plugins. `unified()` is
        // the remark/rehype pipeline Astro used before, which runs `remarkBasePath`; MDX pages
        // take the processor from here too.
        processor: unified({
            remarkPlugins: [[remarkBasePath, { base: BASE }]],
        }),
    },
    // Astro 7's default ('jsx') drops the line breaks between elements, and with them the
    // visible space between inline elements written on separate lines. `true` is what Astro 6
    // did: it removes only whitespace that doesn't show.
    compressHTML: true,
    // The eventual deploy URL — used for canonical links and the sitemap when we
    // wire those up. Placeholder for now.
    site: "https://ekbatan.dev",
});
