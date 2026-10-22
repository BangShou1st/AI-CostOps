import fs from 'node:fs';
import path from 'node:path';

const rootDir = process.cwd();
const docsDir = path.join(rootDir, 'docs');

const markdownFiles = [];

function collectMarkdownFiles(dir) {
  if (!fs.existsSync(dir)) return;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.name === 'node_modules' || entry.name === '.git' || entry.name === 'target' || entry.name === 'dist') {
      continue;
    }
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      collectMarkdownFiles(fullPath);
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      markdownFiles.push(fullPath);
    }
  }
}

// Collect root markdown files
const rootFiles = ['README.md', 'PROJECT_CONTEXT.md', 'CONTRIBUTING.md', 'CHANGELOG.md', 'AGENTS.md'];
for (const rf of rootFiles) {
  const p = path.join(rootDir, rf);
  if (fs.existsSync(p)) {
    markdownFiles.push(p);
  }
}
// Collect docs markdown files
collectMarkdownFiles(docsDir);

console.log(`[verify-doc-links] Found ${markdownFiles.length} Markdown files to inspect.`);

// Regex for markdown links: [text](link)
const linkRegex = /\[([^\]]+)\]\(([^)]+)\)/g;

let totalLinks = 0;
let brokenLinks = 0;
const errors = [];

for (const filePath of markdownFiles) {
  const content = fs.readFileSync(filePath, 'utf-8');
  let match;
  while ((match = linkRegex.exec(content)) !== null) {
    let rawLink = match[2].trim();
    totalLinks++;

    // Strip surrounding angle brackets like <...>
    if (rawLink.startsWith('<') && rawLink.endsWith('>')) {
      rawLink = rawLink.slice(1, -1).trim();
    }

    // Ignore external URLs, anchors, mailto, protocol links
    if (
      rawLink.startsWith('http://') ||
      rawLink.startsWith('https://') ||
      rawLink.startsWith('mailto:') ||
      rawLink.startsWith('#') ||
      rawLink.startsWith('conversation://') ||
      rawLink.startsWith('file://')
    ) {
      continue;
    }

    // Strip anchor and query
    const cleanLink = rawLink.split('#')[0].split('?')[0].trim();
    if (!cleanLink) {
      continue; // Pure anchor
    }

    // Handle absolute drive paths vs relative paths
    let targetPath;
    if (/^[A-Za-z]:[\\/]/.test(cleanLink)) {
      targetPath = path.normalize(cleanLink);
    } else {
      targetPath = path.resolve(path.dirname(filePath), cleanLink);
    }

    if (!fs.existsSync(targetPath)) {
      brokenLinks++;
      const relativeSource = path.relative(rootDir, filePath);
      errors.push({
        source: relativeSource,
        link: rawLink,
        target: path.relative(rootDir, targetPath)
      });
    }
  }
}

console.log(`[verify-doc-links] Checked ${totalLinks} links.`);
if (brokenLinks === 0) {
  console.log(`[verify-doc-links] SUCCESS: 0 broken links found! All local document links are valid.`);
  process.exit(0);
} else {
  console.error(`[verify-doc-links] ERROR: Found ${brokenLinks} broken link(s):`);
  for (const err of errors) {
    console.error(`  - In ${err.source}: [${err.link}] -> Resolved to non-existent: ${err.target}`);
  }
  process.exit(1);
}
