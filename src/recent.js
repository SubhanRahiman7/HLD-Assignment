let recent = [];

function getRecent() { return recent.slice(0, 6); }
function setRecent(arr) { recent = arr.slice(0, 6); }
function addRecent(q) {
 const k = q.toLowerCase();
 recent = [q, ...recent.filter((r) => r.toLowerCase() !== k)].slice(0, 6);
}

module.exports = { getRecent, setRecent, addRecent };
