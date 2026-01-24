const data = {{DATA}};
const metricsOptions = {{OPTIONS}};
const defaultSize = '{{DEFAULT_SIZE}}';
const defaultColor = '{{DEFAULT_COLOR}}';

// State
let currentNode = data;
let pathStack = [];

// DOM elements
const treemapEl = document.getElementById('treemap');
const breadcrumbEl = document.getElementById('breadcrumb');
const infoPanelEl = document.getElementById('info-panel');
const sizeSelect = document.getElementById('size-select');
const colorSelect = document.getElementById('color-select');
const showLabelsCheckbox = document.getElementById('show-labels');
const searchInput = document.getElementById('search-input');
const suggestionsEl = document.getElementById('suggestions');

// Build list of all searchable symbols
function getAllSymbols(node, path = []) {
  const symbols = [];
  if (node.children) {
    for (const child of node.children) {
      const childPath = [...path, child.name];
      if (child.metrics) {
        // It's a function - use ns/name format
        symbols.push({ name: `${child.ns}/${child.name}`, node: child, path: childPath });
      } else {
        // It's a namespace
        symbols.push({ name: childPath.join('.'), node: child, path: childPath });
        symbols.push(...getAllSymbols(child, childPath));
      }
    }
  }
  return symbols;
}

let allSymbols = [];
let selectedSuggestionIdx = -1;
let currentMatches = [];

function initSearch() {
  allSymbols = getAllSymbols(data);

  searchInput.addEventListener('input', () => {
    const query = searchInput.value;
    if (!query) {
      suggestionsEl.classList.remove('visible');
      clearSearchHighlights();
      currentMatches = [];
      return;
    }

    // Find matching symbols (prefix match)
    currentMatches = allSymbols.filter(s => s.name.startsWith(query)).slice(0, 20);

    // Show suggestions
    selectedSuggestionIdx = -1;
    if (currentMatches.length > 0) {
      suggestionsEl.innerHTML = currentMatches.map((m, i) => {
        const matched = m.name.slice(0, query.length);
        const unmatched = m.name.slice(query.length);
        return `<div class="suggestion" data-index="${i}"><span class="matched">${matched}</span><span class="unmatched">${unmatched}</span></div>`;
      }).join('');
      suggestionsEl.classList.add('visible');

      // Add click handlers
      suggestionsEl.querySelectorAll('.suggestion').forEach(el => {
        el.addEventListener('click', () => {
          const idx = parseInt(el.dataset.index);
          selectSuggestion(currentMatches[idx]);
        });
      });
    } else {
      suggestionsEl.classList.remove('visible');
    }

    // Highlight matching cells
    highlightMatches(query);
  });

  searchInput.addEventListener('keydown', (e) => {
    const suggestions = suggestionsEl.querySelectorAll('.suggestion');
    if (!suggestionsEl.classList.contains('visible') || suggestions.length === 0) return;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      selectedSuggestionIdx = Math.min(selectedSuggestionIdx + 1, suggestions.length - 1);
      updateSelectedSuggestion(suggestions);
      // Highlight the selected suggestion
      if (currentMatches[selectedSuggestionIdx]) {
        highlightMatches(currentMatches[selectedSuggestionIdx].name);
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      selectedSuggestionIdx = Math.max(selectedSuggestionIdx - 1, 0);
      updateSelectedSuggestion(suggestions);
      // Highlight the selected suggestion
      if (currentMatches[selectedSuggestionIdx]) {
        highlightMatches(currentMatches[selectedSuggestionIdx].name);
      }
    } else if (e.key === 'Enter' && selectedSuggestionIdx >= 0) {
      e.preventDefault();
      if (currentMatches[selectedSuggestionIdx]) {
        selectSuggestion(currentMatches[selectedSuggestionIdx]);
      }
    } else if (e.key === 'Escape') {
      suggestionsEl.classList.remove('visible');
      searchInput.blur();
      clearSearchHighlights();
    }
  });

  searchInput.addEventListener('blur', () => {
    // Delay to allow click on suggestion
    setTimeout(() => suggestionsEl.classList.remove('visible'), 150);
  });
}

function updateSelectedSuggestion(suggestions) {
  suggestions.forEach((el, i) => {
    el.classList.toggle('selected', i === selectedSuggestionIdx);
  });
  if (selectedSuggestionIdx >= 0) {
    suggestions[selectedSuggestionIdx].scrollIntoView({ block: 'nearest' });
  }
}

function selectSuggestion(match) {
  searchInput.value = '';
  suggestionsEl.classList.remove('visible');
  clearSearchHighlights();

  // Navigate to the symbol's namespace
  const path = match.node.metrics
    ? match.path.slice(0, -1)  // For functions, go to parent namespace
    : match.path;              // For namespaces, go to the namespace itself

  pathStack = path;
  currentNode = data;
  for (const name of pathStack) {
    currentNode = currentNode.children.find(c => c.name === name);
  }
  render();
}

function highlightMatches(query) {
  clearSearchHighlights();

  let targetName = null;

  // Check for exact match first
  const exactMatch = allSymbols.find(s => s.name === query);
  if (exactMatch) {
    targetName = query;
  } else {
    // Find longest complete prefix (up to last delimiter)
    const lastDot = query.lastIndexOf('.');
    const lastSlash = query.lastIndexOf('/');
    const lastDelim = Math.max(lastDot, lastSlash);

    if (lastDelim > 0) {
      targetName = query.slice(0, lastDelim);
    }
  }

  if (targetName) {
    // Build list of all ancestors to highlight
    const toHighlight = new Set();
    toHighlight.add(targetName);

    // Add namespace ancestors (split by dot)
    // For 'foo.bar.baz/fn' -> highlight 'foo', 'foo.bar', 'foo.bar.baz', 'foo.bar.baz/fn'
    // For 'foo.bar.baz' -> highlight 'foo', 'foo.bar', 'foo.bar.baz'
    const slashIdx = targetName.indexOf('/');
    const nsPart = slashIdx > 0 ? targetName.slice(0, slashIdx) : targetName;
    const parts = nsPart.split('.');
    let acc = '';
    for (const part of parts) {
      acc = acc ? acc + '.' + part : part;
      toHighlight.add(acc);
    }

    treemapEl.querySelectorAll('.cell').forEach(cell => {
      if (toHighlight.has(cell.dataset.fullName)) {
        const overlay = document.createElement('div');
        overlay.className = 'search-overlay';
        overlay.style.left = cell.style.left;
        overlay.style.top = cell.style.top;
        overlay.style.width = cell.style.width;
        overlay.style.height = cell.style.height;
        treemapEl.appendChild(overlay);
      }
    });
  }
}

function clearSearchHighlights() {
  treemapEl.querySelectorAll('.search-overlay').forEach(el => el.remove());
}

// Initialize dropdowns
function initDropdowns() {
  metricsOptions.forEach(opt => {
    sizeSelect.innerHTML += `<option value="${opt.key}">${opt.label}</option>`;
    colorSelect.innerHTML += `<option value="${opt.key}">${opt.label}</option>`;
  });
  sizeSelect.value = defaultSize;
  colorSelect.value = defaultColor;
  sizeSelect.addEventListener('change', render);
  colorSelect.addEventListener('change', render);
  showLabelsCheckbox.addEventListener('change', render);
}

// Update breadcrumb
function updateBreadcrumb() {
  if (pathStack.length === 0) {
    breadcrumbEl.innerHTML = '';
    return;
  }
  breadcrumbEl.innerHTML = pathStack.map((p, i) => {
    const isLast = i === pathStack.length - 1;
    return `<span class="${isLast ? 'current' : ''}" data-index="${i}">${p}</span>`;
  }).join('.');

  breadcrumbEl.querySelectorAll('span:not(.current)').forEach(span => {
    span.addEventListener('click', () => {
      const idx = parseInt(span.dataset.index);
      navigateToLevel(idx);
    });
  });
}

// Navigate to breadcrumb level
function navigateToLevel(idx) {
  pathStack = pathStack.slice(0, idx + 1);
  currentNode = data;
  for (const name of pathStack) {
    currentNode = currentNode.children.find(c => c.name === name);
  }
  render();
}

// Drill down into namespace
function drillDown(node) {
  if (node.children && node.children.length > 0) {
    pathStack.push(node.name);
    currentNode = node;
    render();
  }
}

// Get all leaf metrics for color scale domain
function getAllLeafMetrics(node, key) {
  const values = [];
  function traverse(n) {
    if (n.metrics) {
      const v = n.metrics[key];
      if (typeof v === 'number') values.push(v);
    }
    if (n.children) n.children.forEach(traverse);
  }
  traverse(node);
  return values;
}

// Render treemap
function render() {
  const sizeKey = sizeSelect.value;
  const colorKey = colorSelect.value;

  treemapEl.innerHTML = '';
  updateBreadcrumb();

  const width = treemapEl.clientWidth;
  const height = treemapEl.clientHeight;

  if (!currentNode.children || currentNode.children.length === 0) {
    treemapEl.innerHTML = '<div style="padding:20px;color:#839496;">No data to display</div>';
    return;
  }

  const root = d3.hierarchy(currentNode)
    .sum(d => {
      if (d.metrics) return Math.max(1, d.metrics[sizeKey] || 1);
      return 0;
    })
    .sort((a, b) => b.value - a.value);

  d3.treemap()
    .size([width, height])
    .padding(0)
    .round(true)(root);

  // Color scale - Solarized-inspired: cold (good) -> hot (bad)
  const colorValues = getAllLeafMetrics(currentNode, colorKey);
  const colorExtent = d3.extent(colorValues);
  const domain = colorExtent[0] === colorExtent[1] ? [0, 1] : colorExtent;
  // Blue to red (darker for white gradient overlay)
  const colorScale = d3.scaleLinear()
    .domain([domain[0], domain[1]])
    .range(['#268bd2', '#dc322f'])
    .clamp(true);

  // Render cells (skip root, skip cells too small to display)
  const minCellSize = 1;
  const nodeToCell = new Map();  // Map d3 node -> DOM element for ancestor highlighting

  root.descendants().slice(1)
    .filter(d => (d.x1 - d.x0) >= minCellSize && (d.y1 - d.y0) >= minCellSize)
    .forEach(d => {
    const cell = document.createElement('div');
    cell.className = 'cell' + (d.children ? ' namespace' : '');

    // Set full name for search matching
    if (d.data.metrics) {
      cell.dataset.fullName = `${d.data.ns}/${d.data.name}`;
    } else {
      const path = d.ancestors().reverse().slice(1).map(a => a.data.name);
      cell.dataset.fullName = path.join('.');
    }

    cell.style.left = d.x0 + 'px';
    cell.style.top = d.y0 + 'px';
    cell.style.width = (d.x1 - d.x0) + 'px';
    cell.style.height = (d.y1 - d.y0) + 'px';

    // Color
    let bgColor;
    if (d.data.metrics) {
      const val = d.data.metrics[colorKey];
      bgColor = (typeof val === 'number' && val !== null) ? colorScale(val) : '#073642';
      const gradient = `linear-gradient(315deg, #fdf6e3a0, ${bgColor})`;
      if (d.data.metrics['failed?']) {
        cell.classList.add('failed');
      }
      if (d.data.metrics['unused?']) {
        // Layer stripe pattern over gradient
        const stripes = 'repeating-linear-gradient(45deg, transparent, transparent 3px, rgba(0,0,0,0.4) 3px, rgba(0,0,0,0.4) 6px)';
        cell.style.background = `${stripes}, ${gradient}`;
      } else {
        cell.style.background = gradient;
      }
    } else {
      // Namespace container - use solid light solarized color
      bgColor = '#657b83';
      cell.style.backgroundColor = bgColor;
    }

    // Add label if enabled
    if (showLabelsCheckbox.checked) {
      const label = document.createElement('div');
      label.className = 'cell-label';
      label.textContent = d.data.name;
      cell.appendChild(label);
    }

    // Click handler - drill down toward the clicked node
    // Shift+click jumps straight to the namespace containing the function
    // Alt+click copies filepath:line to clipboard
    cell.addEventListener('click', (e) => {
      // Alt/Option+click - copy symbol name
      if (e.altKey) {
        const nodeData = d.data;
        const text = nodeData.ns ? `${nodeData.ns}/${nodeData.name}` : nodeData.name;
        if (text) navigator.clipboard.writeText(text);
        return;
      }

      // Get path from root to this node (excluding root)
      const ancestors = d.ancestors().reverse().slice(1);

      if (e.shiftKey) {
        // Jump to deepest namespace (parent of leaf, or the node itself if it's a namespace)
        const targetAncestors = d.data.metrics
          ? ancestors.slice(0, -1)  // exclude the function itself
          : ancestors;              // namespace - include all

        if (targetAncestors.length === 0) return;

        // Build new path from current level
        const currentDepth = pathStack.length;
        const newPathParts = targetAncestors.slice(currentDepth).map(a => a.data.name);

        if (newPathParts.length === 0) return;

        // Add all levels to pathStack
        pathStack = [...pathStack, ...newPathParts];
        currentNode = data;
        for (const name of pathStack) {
          currentNode = currentNode.children.find(c => c.name === name);
        }
        render();
      } else {
        // Normal click - drill one level deeper
        const nextNode = currentNode.children?.find(c =>
          ancestors.some(a => a.data.name === c.name)
        );
        if (nextNode && nextNode.children) {
          drillDown(nextNode);
        }
      }
    });

    // Store mapping for ancestor highlighting
    nodeToCell.set(d, cell);

    // Highlight ancestors on hover using overlay divs
    cell.addEventListener('mouseenter', () => {
      showInfo(d);
      // Create overlays for this cell and ancestors
      let node = d;
      let isFirst = true;
      while (node) {
        const nodeCell = nodeToCell.get(node);
        if (nodeCell) {
          const overlay = document.createElement('div');
          overlay.className = 'highlight-overlay' + (isFirst ? '' : ' ancestor');
          overlay.style.left = nodeCell.style.left;
          overlay.style.top = nodeCell.style.top;
          overlay.style.width = nodeCell.style.width;
          overlay.style.height = nodeCell.style.height;
          treemapEl.appendChild(overlay);
        }
        isFirst = false;
        node = node.parent;
      }
    });
    cell.addEventListener('mouseleave', () => {
      hideInfo();
      // Remove all overlays
      treemapEl.querySelectorAll('.highlight-overlay').forEach(el => el.remove());
    });

    treemapEl.appendChild(cell);
  });

  // Add top-level border overlays (for direct children of current root)
  root.children?.forEach(child => {
    const border = document.createElement('div');
    border.className = 'top-level-border';
    border.style.left = child.x0 + 'px';
    border.style.top = child.y0 + 'px';
    border.style.width = (child.x1 - child.x0) + 'px';
    border.style.height = (child.y1 - child.y0) + 'px';
    treemapEl.appendChild(border);
  });
}

// Info panel functions
function showInfo(d) {
  const nodeData = d.data;
  // Compute full path for display
  let displayName;
  if (nodeData.ns) {
    displayName = `${nodeData.ns}/${nodeData.name}`;
  } else {
    const path = d.ancestors().reverse().slice(1).map(a => a.data.name);
    displayName = path.join('.');
  }

  let html = `<span class="name">${displayName}</span>`;

  if (nodeData.metrics) {
    metricsOptions.forEach(opt => {
      const val = nodeData.metrics[opt.key];
      if (val !== undefined) {
        const displayVal = val === null ? '-' : val;
        html += `<span class="metric">${opt.label}: <span class="metric-value">${displayVal}</span></span>`;
      }
    });
    if (nodeData.metrics['failed?']) {
      html += '<span class="badge failed">Analysis Failed</span>';
    } else if (nodeData.metrics['unused?']) {
      html += '<span class="badge unused">Unused</span>';
    }
  } else {
    // Summary for namespace
    const leaves = [];
    function collectLeaves(n) {
      if (n.metrics) leaves.push(n);
      if (n.children) n.children.forEach(collectLeaves);
    }
    collectLeaves(nodeData);
    const failedCount = leaves.filter(l => l.metrics['failed?']).length;
    const analyzedCount = leaves.length - failedCount;
    html += `<span class="metric">Functions: <span class="metric-value">${analyzedCount}</span></span>`;
    if (failedCount > 0) {
      html += `<span class="metric">Failed: <span class="metric-value">${failedCount}</span></span>`;
    }
    const unusedCount = leaves.filter(l => l.metrics['unused?']).length;
    if (unusedCount > 0) {
      html += `<span class="metric">Unused: <span class="metric-value">${unusedCount}</span></span>`;
    }
  }

  infoPanelEl.innerHTML = html;
}

function hideInfo() {
  infoPanelEl.innerHTML = '';
}

// Initialize
initDropdowns();
initSearch();
render();

// Handle resize
window.addEventListener('resize', render);

// Handle keyboard shortcuts
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    // Clear search first if active
    if (searchInput.value) {
      searchInput.value = '';
      suggestionsEl.classList.remove('visible');
      clearSearchHighlights();
      return;
    }
    // Then zoom out
    if (pathStack.length > 0) {
      pathStack.pop();
      currentNode = data;
      for (const name of pathStack) {
        currentNode = currentNode.children.find(c => c.name === name);
      }
      render();
    }
  }
});
