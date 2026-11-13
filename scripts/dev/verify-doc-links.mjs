import fs from 'node:fs';
import path from 'node:path';

const rootDir = process.cwd();
const ignoredDirectories = new Set([
  '.git',
  '.local-dev',
  '.trivy-cache',
  'build',
  'coverage',
  'dist',
  'logs',
  'node_modules',
  'playwright-report',
  'target',
  'test-results'
]);

const markdownFiles = [];

function collectMarkdownFiles(dir) {
  if (!fs.existsSync(dir)) return;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (ignoredDirectories.has(entry.name)) {
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

collectMarkdownFiles(rootDir);

console.log(`[verify-doc-links] Found ${markdownFiles.length} Markdown files to inspect.`);

const markdownLinkRegex = /\[([^\]]+)\]\(([^)]+)\)/g;
const htmlLinkRegex = /\b(?:src|href)\s*=\s*["']([^"']+)["']/gi;

let totalLinks = 0;
let brokenLinks = 0;
const errors = [];

function isExternal(rawLink) {
  return (
    rawLink.startsWith('http://') ||
    rawLink.startsWith('https://') ||
    rawLink.startsWith('//') ||
    rawLink.startsWith('mailto:') ||
    rawLink.startsWith('#') ||
    rawLink.startsWith('conversation://') ||
    rawLink.startsWith('file://') ||
    rawLink.startsWith('data:')
  );
}

function normalizeLink(rawLink) {
  let link = rawLink.trim();
  if (link.startsWith('<') && link.endsWith('>')) {
    link = link.slice(1, -1).trim();
  }
  return link;
}

function checkLink(filePath, rawLink) {
  let link = normalizeLink(rawLink);
  totalLinks++;

  if (isExternal(link)) {
    return;
  }

  const cleanLink = link.split('#')[0].split('?')[0].trim();
  if (!cleanLink) {
    return;
  }

  let targetPath;
  if (/^[A-Za-z]:[\\/]/.test(cleanLink)) {
    targetPath = path.normalize(cleanLink);
  } else {
    targetPath = path.resolve(path.dirname(filePath), cleanLink);
  }

  if (!fs.existsSync(targetPath)) {
    brokenLinks++;
    errors.push({
      source: path.relative(rootDir, filePath),
      link: rawLink,
      target: path.relative(rootDir, targetPath)
    });
  }
}

for (const filePath of markdownFiles) {
  const content = fs.readFileSync(filePath, 'utf-8');

  let match;
  while ((match = markdownLinkRegex.exec(content)) !== null) {
    checkLink(filePath, match[2]);
  }

  while ((match = htmlLinkRegex.exec(content)) !== null) {
    checkLink(filePath, match[1]);
  }
}

console.log(`[verify-doc-links] Checked ${totalLinks} links.`);
if (brokenLinks === 0) {
  console.log('[verify-doc-links] SUCCESS: 0 broken links found! All local document links are valid.');
  process.exit(0);
}

console.error(`[verify-doc-links] ERROR: Found ${brokenLinks} broken link(s):`);
for (const err of errors) {
  console.error(`  - In ${err.source}: [${err.link}] -> Resolved to non-existent: ${err.target}`);
}
process.exit(1);
