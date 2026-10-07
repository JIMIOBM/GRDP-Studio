// ECLIPSE File Formats Reference Manual, EGRID / Cell Geometry:
// ZCORN is (2*NX, 2*NY, 2*NZ), with I varying fastest (not eight values per cell).
// Indices i/j/k below are zero-based; returned corners are top 00/10/01/11 then bottom.
export function zcornCornerIndices(nx, ny, i, j, k) {
  const row = 2 * nx
  const plane = row * 2 * ny
  const base = 2 * i + 2 * j * row + 2 * k * plane
  return [base, base + 1, base + row, base + row + 1,
    base + plane, base + plane + 1, base + plane + row, base + plane + row + 1]
}
