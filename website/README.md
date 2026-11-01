# Ekbatan website

The marketing + docs site for [Ekbatan](https://github.com/ekbatan-io/ekbatan). Built with [Astro](https://astro.build) + MDX.

## Information architecture

Four top-level sections, mapped to the [Diátaxis](https://diataxis.fr) quadrants:

| Section          | URL           | Purpose                                                          | Voice                          |
| ---------------- | ------------- | ---------------------------------------------------------------- | ------------------------------ |
| **Landing**      | `/`           | Hook + one install snippet + clear paths into the other sections | Punchy, marketing              |
| **Concepts**     | `/concepts/`  | _Why_ the framework exists, the model behind it                  | Conceptual, opinionated        |
| **Learn**        | `/learn/`     | Step-by-step tutorial with a stack picker                        | Hand-holding, narrative        |
| **Reference**    | `/reference/` | API + conventions, exhaustively                                  | Dense, lookup-oriented         |

The tutorial under `/learn/` uses a `<StackPicker />` component that swaps snippets per (stack × build tool × dialect) instead of forking the tutorial into many pages. The framework wallet examples live in [`../ekbatan-examples/`](../ekbatan-examples) and back the tutorial code samples; plain Java selections point to manual wiring references.

## Local development

Needs Node 22.22 or later (Astro 7's minimum). The deploy workflow builds with Node 26.

```bash
cd website
npm install      # first time only
npm run dev      # → http://localhost:4321
```

## Production build

```bash
npm run build    # → dist/ (static files)
npm run preview  # serves dist/ on http://127.0.0.1:4322
```

## Source-of-truth convention

The repository keeps `docs/*.md` and `website/src/pages/**/*.mdx` deliberately in sync — `docs/` is for GitHub viewers and IDE readers, `website/` is the rendered site. Today this is enforced **by hand**: when you edit one, you must also update the other. Two rules:

1. **For pages that originated in `docs/`** (21 of them — `docs/concepts/{outbox,actions,models-and-entities}.md`, `docs/wiring/*.md`, `docs/runtime/*.md`, `docs/database/{sharding,tables,outbox-schema,keyed-locks,transaction-manager,repositories}.md`, `docs/database/tables/*.md`, `docs/jobs/distributed-jobs.md`, `docs/events/*.md`): edit `docs/` first, then mirror the change into the matching `website/src/pages/*.mdx`. The MDX version differs only in (a) frontmatter wrapping (`title`, `lead`, `activeSection`, `layout`) and (b) link rewrites (`(../wiring/spring.md)` → `(/reference/di/spring-boot/)`).
2. **For pages that originated on the website** (currently `concepts/the-dual-write-trap`, `concepts/sharding`, `learn/getting-started`, `learn/complete-project-setup`, `reference/action`): if there's no `docs/` counterpart yet, **create one at the same time**. Same conversion in reverse — strip the Astro frontmatter, hoist `title` to `# H1`, emit `lead` as the first paragraph, rewrite Astro routes back to relative `.md` links.

A planned (deferred) refactor to Astro Content Layer with a `glob({ base: '../docs' })` loader would eliminate the duplication entirely — `docs/*.md` would become the single source and the website would read it in place. Until then, the convention above is the discipline that keeps drift out.

## Why Astro

The visual identity is custom (brutalist, paper + ink + orange + yellow, Space Grotesk display), so a docs framework with an opinionated theme (VitePress, Docusaurus) would be fighting us. Astro's blank-slate approach lets us reuse the existing prebuilt CSS bundle unchanged while adding islands of interactivity (the StackPicker) without shipping a SPA-sized JS payload to every visitor. Output is mostly static HTML — deploys to any static host.

## File layout

```
website/
├── astro.config.mjs
├── package.json
├── tsconfig.json
├── public/
│   └── fonts/                         # self-hosted woff2 (Space Grotesk, DM Sans, JetBrains Mono)
└── src/
    ├── layouts/
    │   └── BaseLayout.astro           # <html> + <head> + <Nav/> + <slot/> + <Footer/>
    ├── components/
    │   ├── Nav.astro                  # global top nav
    │   ├── Footer.astro               # global footer
    │   ├── StackPicker.astro          # interactive picker used in /learn/
    │   └── DatabasePicker.astro       # database-only picker used in table references
    ├── pages/
    │   ├── index.astro                # /
    │   ├── concepts/
    │   │   ├── index.astro            # /concepts/
    │   │   └── *.mdx
    │   ├── learn/
    │   │   ├── index.astro            # /learn/
    │   │   └── *.mdx
    │   └── reference/
    │       ├── index.astro            # /reference/
    │       └── **/*.mdx
    └── styles/
        ├── styles.css                 # the compiled Tailwind bundle (frozen)
        └── fonts.css                  # @font-face → /fonts/*.woff2
```
