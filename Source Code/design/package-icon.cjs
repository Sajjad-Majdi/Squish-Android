// Export the approved raster artwork. Run with Node and sharp on NODE_PATH.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const sharp = require('sharp');
const design = __dirname;
const source = path.dirname(design);
const res = path.join(source, 'app/src/main/res');
const input = path.join(design, 'foreground-master.png');
const transparent = {r:0,g:0,b:0,alpha:0};
const sizes = [['mdpi',108],['hdpi',162],['xhdpi',216],['xxhdpi',324],['xxxhdpi',432]];
const hash = b => crypto.createHash('sha256').update(b).digest('hex');
function inventory(dir, result={}) {
  for (const entry of fs.readdirSync(dir,{withFileTypes:true})) {
    const file=path.join(dir,entry.name);
    if (entry.isDirectory()) {
      if (!['design','build','.gradle','.kotlin'].includes(entry.name)) inventory(file,result);
    } else result[path.relative(source,file).replaceAll('\\','/')]=hash(fs.readFileSync(file));
  }
  return result;
}
const allowed = p => /app\/src\/main\/res\/(mipmap-[^/]+\/ic_launcher(?:_foreground|_background|_monochrome)?\.(png|xml)|drawable\/ic_launcher_(bg|fg|mono)\.xml)$/.test(p);
function svg(body,w=1024,h=w) {return Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`);}
function shape(name,n) {
  if(name==='circle') return `<circle cx="${n/2}" cy="${n/2}" r="${n/2}" fill="#fff"/>`;
  if(name==='rounded-square') return `<rect width="${n}" height="${n}" rx="${n*.23}" fill="#fff"/>`;
  const pts=[];
  for(let i=0;i<=360;i++) {const t=i*Math.PI/180,c=Math.cos(t),s=Math.sin(t);pts.push(`${i?'L':'M'}${n/2+n/2*Math.sign(c)*Math.pow(Math.abs(c),.5)},${n/2+n/2*Math.sign(s)*Math.pow(Math.abs(s),.5)}`);}
  return `<path d="${pts.join(' ')}Z" fill="#fff"/>`;
}
async function bg(n) {
  const b=Buffer.alloc(n*n*3);
  for(let y=0;y<n;y++) for(let x=0;x<n;x++) {
    const nx=x/(n-1),ny=y/(n-1),glow=Math.exp(-((nx-.35)**2+(ny-.25)**2)/.32),i=(y*n+x)*3;
    b[i]=Math.round(35+12*glow+3*(1-ny));
    b[i+1]=Math.round(72+30*glow+6*(1-ny));
    b[i+2]=Math.round(209+35*glow+5*(1-ny));
  }
  return sharp(b,{raw:{width:n,height:n,channels:3}}).png({compressionLevel:9}).toBuffer();
}
async function main() {
  const baselineFile=path.join(design,'source-baseline.json');
  if(!fs.existsSync(baselineFile)) fs.writeFileSync(baselineFile,JSON.stringify(inventory(source),null,2));
  const {data,info}=await sharp(input).ensureAlpha().raw().toBuffer({resolveWithObject:true});
  let left=info.width,top=info.height,right=0,bottom=0;
  for(let y=0;y<info.height;y++) for(let x=0;x<info.width;x++) {
    const i=(y*info.width+x)*4;
    if(data[i+3]<16) {data[i]=data[i+1]=data[i+2]=data[i+3]=0;continue;}
    // Keep extreme generated highlights blue-tinted. No visible pure white/black.
    if(data[i]>246 && data[i+1]>246 && data[i+2]>246) {data[i]=224;data[i+1]=245;data[i+2]=254;}
    if(data[i]===0 && data[i+1]===0 && data[i+2]===0) {data[i]=28;data[i+1]=61;data[i+2]=139;}
    left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
  }
  const cx=(left+right)/2,cy=(top+bottom)/2;
  let radius=0;
  for(let y=top;y<=bottom;y++) for(let x=left;x<=right;x++) if(data[(y*info.width+x)*4+3]) radius=Math.max(radius,Math.hypot(x-cx,y-cy));
  const crop={left,top,width:right-left+1,height:bottom-top+1};
  const clean=await sharp(data,{raw:{width:info.width,height:info.height,channels:4}}).extract(crop).png().toBuffer();
  // Exact artwork alpha is reused for the themed icon; the internal chamber is removed.
  const monoSource=Buffer.alloc(data.length);
  const r=Math.round(info.width*.042);
  const glyphSvg=svg('<path d="M 231 739 Q 181 730 205 666 L 394 430 Q 429 384 472 432 L 683 674 Q 721 757 651 762 L 267 765 Q 240 765 231 739 Z" fill="#fff"/><circle cx="638" cy="380" r="68" fill="#fff"/>',1000);
  const glyph=await sharp(glyphSvg).resize(info.width,info.height).ensureAlpha().raw().toBuffer();
  for(let y=top;y<=bottom;y++) {
    let a=-1,z=-1;
    for(let x=left;x<=right;x++) if(data[(y*info.width+x)*4+3]>=128){if(a<0)a=x;z=x;}
    for(let x=Math.max(0,a);x<=z;x++) {
      const i=(y*info.width+x)*4;
      const rim=x<a+r || x>z-r || y<top+r || y>bottom-r;
      monoSource[i]=monoSource[i+1]=monoSource[i+2]=255;
      monoSource[i+3]=rim?data[i+3]:Math.min(data[i+3],glyph[i+3]);
    }
  }
  // Smooth raster-to-silhouette sampling before density export.
  const monoClean=await sharp(monoSource,{raw:{width:info.width,height:info.height,channels:4}}).extract(crop).blur(.7).png().toBuffer();
  async function foreground(n,mono=false) {
    const scale=(31.7*n/108)/radius;
    const w=Math.round(crop.width*scale),h=Math.round(crop.height*scale);
    const object=await sharp(mono?monoClean:clean).resize(w,h).png().toBuffer();
    const layer=await sharp({create:{width:n,height:n,channels:4,background:transparent}}).composite([{input:object,left:Math.floor((n-w)/2),top:Math.floor((n-h)/2)}]).raw().toBuffer();
    // Resampling may leave faint alpha outside the safe circle. Clip only that padding.
    for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(Math.hypot(x+.5-n/2,y+.5-n/2)*108/n>32.8)layer[(y*n+x)*4+3]=0;
    return sharp(layer,{raw:{width:n,height:n,channels:4}}).png().toBuffer();
  }
  const report={densities:[],foregroundSafeCircleDp:63.4,canvasDp:108,maskViewportDp:72};
  for(const [density,n] of sizes) {
    const dir=path.join(res,`mipmap-${density}`);fs.mkdirSync(dir,{recursive:true});
    for(const [name,image] of [['foreground',await foreground(n)],['background',await bg(n)],['monochrome',await foreground(n,true)]]) {
      let out;
      if(name==='foreground') out=await sharp(image).png({palette:true,colours:128,dither:.25,compressionLevel:9,effort:10}).toBuffer();
      else if(name==='monochrome') {
        const {data:b,info:m}=await sharp(image).ensureAlpha().raw().toBuffer({resolveWithObject:true});
        for(let i=0;i<b.length;i+=4)b[i]=b[i+1]=b[i+2]=255;
        out=await sharp(b,{raw:{width:m.width,height:m.height,channels:4}}).png({palette:true,colours:256,dither:0,compressionLevel:9,effort:10}).toBuffer();
      } else out=image;
      const file=path.join(dir,`ic_launcher_${name}.png`);fs.writeFileSync(file,out);
      const {data:b,info:m}=await sharp(out).ensureAlpha().raw().toBuffer({resolveWithObject:true});
      let maxRadius=0,opaque=0,empty=0,pure=0;
      for(let y=0;y<n;y++)for(let x=0;x<n;x++) {
        const i=(y*n+x)*4;
        if(b[i+3]===0)empty++;else{
          if(b[i+3]===255)opaque++;
          maxRadius=Math.max(maxRadius,Math.hypot(x+.5-n/2,y+.5-n/2)*108/n);
          if(name!=='monochrome'&&((b[i]===255&&b[i+1]===255&&b[i+2]===255)||(b[i]===0&&b[i+1]===0&&b[i+2]===0)))pure++;
          if(name==='monochrome'&&(b[i]!==255||b[i+1]!==255||b[i+2]!==255))throw Error('Monochrome is not white');
        }
      }
      if(m.width!==n||m.height!==n||pure)throw Error('Dimensions or palette failed');
      if(name==='background'&&opaque!==n*n)throw Error('Background not opaque');
      if(name!=='background'&&(!empty||maxRadius>33))throw Error(`Alpha or 66dp safe circle failed: ${density}/${name} radius ${maxRadius} empty ${empty}`);
      report.densities.push({density,name,pixels:n,bytes:out.length,transparentPixels:empty,maxVisibleRadiusDp:Math.round(maxRadius*100)/100,sha256:hash(out)});
    }
  }
  report.launcherPngBytes=report.densities.reduce((s,r)=>s+r.bytes,0);
  if(report.launcherPngBytes>=150000)throw Error(`Launcher budget exceeded: ${report.launcherPngBytes}`);
  const xml='<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@mipmap/ic_launcher_background" />\n    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n</adaptive-icon>\n';
  fs.writeFileSync(path.join(res,'mipmap-anydpi-v26/ic_launcher.xml'),xml);
  for(const name of ['bg','fg','mono']) {
    const file=path.join(res,`drawable/ic_launcher_${name}.xml`);
    if(fs.existsSync(file))fs.unlinkSync(file);
  }
  async function icon(n,mask,mono=false,theme='light') {
    const canvas=Math.round(n*1.5),offset=Math.round(canvas/6);
    let image;
    if(mono) {
      const tint=theme==='light'?'#275587':'#A8D4FF',back=theme==='light'?'#CEDFF2':'#293E64';
      const m=await sharp(await foreground(canvas,true)).extract({left:offset,top:offset,width:n,height:n}).ensureAlpha().raw().toBuffer();
      const rgb=tint.match(/[0-9A-F]{2}/gi).map(h=>parseInt(h,16));
      for(let i=0;i<m.length;i+=4){m[i]=rgb[0];m[i+1]=rgb[1];m[i+2]=rgb[2];}
      const rgba=await sharp(m,{raw:{width:n,height:n,channels:4}}).png().toBuffer();
      image=await sharp(svg(`<rect width="${n}" height="${n}" fill="${back}"/>`,n)).composite([{input:rgba}]).png().toBuffer();
    } else {
      const full=await sharp(await bg(canvas)).composite([{input:await foreground(canvas)}]).png().toBuffer();
      image=await sharp(full).extract({left:offset,top:offset,width:n,height:n}).png().toBuffer();
    }
    if(mask)image=await sharp(image).composite([{input:svg(shape(mask,n),n),blend:'dest-in'}]).png().toBuffer();
    return image;
  }
  const play=await icon(512,null);fs.writeFileSync(path.join(design,'play-store-512.png'),play);
  fs.writeFileSync(path.join(design,'final-circle-512.png'),await icon(512,'circle'));
  const names=['circle','squircle','rounded-square'];
  let body='<rect width="1024" height="1024" fill="#E1EAF8"/><text x="48" y="66" font-family="Segoe UI,sans-serif" font-size="30" font-weight="600" fill="#234572">Squish · pinched glass media</text><text x="48" y="99" font-family="Segoe UI,sans-serif" font-size="16" fill="#486994">Circle first · actual adaptive crop · native 48 px samples</text>';
  const composites=[];
  for(let k=0;k<3;k++) {
    const x=48+k*312;
    body+=`<rect x="${x}" y="130" width="288" height="428" rx="24" fill="#F0F5FC"/><text x="${x+144}" y="166" text-anchor="middle" font-family="Segoe UI,sans-serif" font-size="18" fill="#234572">${names[k]}</text><text x="${x+144}" y="536" text-anchor="middle" font-family="Segoe UI,sans-serif" font-size="14" fill="#486994">224 px / 48 px</text>`;
    composites.push({input:await icon(224,names[k]),left:x+32,top:195},{input:await icon(48,names[k]),left:x+120,top:467});
  }
  body+='<rect x="48" y="590" width="912" height="140" rx="24" fill="#263D61"/><text x="72" y="622" font-family="Segoe UI,sans-serif" font-size="18" fill="#C9DFFD">48 px on tinted dark wallpaper</text>';
  for(let k=0;k<3;k++)composites.push({input:await icon(48,names[k]),left:168+k*312,top:654});
  body+='<text x="48" y="787" font-family="Segoe UI,sans-serif" font-size="18" fill="#234572">Android themed silhouette · light / dark</text>';
  for(let k=0;k<3;k++) {
    composites.push({input:await icon(72,names[k],true,'light'),left:120+k*312,top:816},{input:await icon(72,names[k],true,'dark'),left:216+k*312,top:816});
  }
  body+='<text x="48" y="971" font-family="Segoe UI,sans-serif" font-size="16" fill="#486994">One intact photo. A gently compressed frame. No arrows, no archive metaphor.</text>';
  fs.writeFileSync(path.join(design,'mask-preview-1024.png'),await sharp(svg(body)).composite(composites).png({compressionLevel:9}).toBuffer());
  const before=JSON.parse(fs.readFileSync(baselineFile));const after=inventory(source);
  report.otherSourceFilesUnchanged=Object.keys({...before,...after}).filter(p=>!allowed(p)&&before[p]!==after[p]);
  if(report.otherSourceFilesUnchanged.length)throw Error(`Unexpected source changes: ${report.otherSourceFilesUnchanged}`);
  fs.writeFileSync(path.join(design,'verification.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({launcherPngBytes:report.launcherPngBytes,files:report.densities.length,safeCircle:'all alpha inside 66 dp circle',otherSourceFilesUnchanged:report.otherSourceFilesUnchanged},null,2));
}
main().catch(e=>{console.error(e);process.exit(1);});
