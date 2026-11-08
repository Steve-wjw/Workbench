# Offline Markdown Dependency

`marked-17.0.5.tgz` is an unmodified npm package built from the locally available
`marked@17.0.5` distribution. Its MIT license is included in the archive.
`package.json` and `package-lock.json` reference this relative file so installing
the project does not depend on a developer's machine-specific path.

Markdown is parsed into tokens and rendered as React elements. Raw HTML is
displayed as text, and link/image protocols are restricted by the renderer.
