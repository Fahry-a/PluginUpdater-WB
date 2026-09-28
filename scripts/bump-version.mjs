#!/usr/bin/env node
// Maps a pure semver (from semantic-release, e.g. 1.0.6) onto this project's
// version scheme: pom.xml and README keep the `26.2-` Minecraft prefix
// (e.g. 26.2-1.0.6), package.json tracks the pure semver.
import { readFileSync, writeFileSync } from 'node:fs';

const version = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(version || '')) {
  console.error(`bump-version: expected semver X.Y.Z, got ${JSON.stringify(version)}`);
  process.exit(1);
}
const full = `26.2-${version}`;

const pom = readFileSync('pom.xml', 'utf8');
const pomNext = pom.replace(/<version>26\.2-\d+\.\d+\.\d+<\/version>/, `<version>${full}</version>`);
if (pomNext === pom) throw new Error('bump-version: pom.xml version pattern not found');
writeFileSync('pom.xml', pomNext);

const readme = readFileSync('README.md', 'utf8');
const readmeNext = readme.replace(/26\.2-\d+\.\d+\.\d+/g, full);
if (readmeNext === readme) throw new Error('bump-version: README.md version pattern not found');
writeFileSync('README.md', readmeNext);

const pkg = JSON.parse(readFileSync('package.json', 'utf8'));
pkg.version = version;
writeFileSync('package.json', JSON.stringify(pkg, null, 2) + '\n');

console.log(`bump-version: bumped to ${full}`);