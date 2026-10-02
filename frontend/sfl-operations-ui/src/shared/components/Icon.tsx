import { type LucideIcon, Activity, ArrowLeft, Bell, Book, Building2, Calendar, Camera, ChevronDown, ChevronLeft, ChevronRight, ChevronUp, ChevronsLeft, ChevronsRight, CircleAlert, CircleCheck, Clipboard, ClipboardList, Clock, Cloud, Coins, Download, Ellipsis, Eye, EyeOff, FileText, Flag, Fuel, Gauge, Inbox, Info, Layers, LayoutDashboard, Link, ListFilter, Lock, MapPin, Megaphone, Menu, Package, Pencil, Play, Plus, RefreshCw, Route, Scale, Search, ShieldAlert, ShieldCheck, Siren, Square, Target, TriangleAlert, Truck, Upload, User, UserPlus, UserRound, Users, Workflow, Wrench, X, Zap } from 'lucide-react';
import type { SVGProps } from 'react';

/**
 * The dashboard's icon set - Lucide, the same set `@rfdtech/components` draws its own chrome with, so
 * a navigation glyph and a library button never come from two different families.
 *
 * Callers keep the semantic names below (`truck`, `alert-circle`) rather than importing Lucide
 * components directly, which is what lets a glyph be swapped in one place.
 */

export type IconName =
  | 'dashboard'
  | 'building'
  | 'layers'
  | 'truck'
  | 'driver'
  | 'route'
  | 'workflow'
  | 'shield-check'
  | 'shield-alert'
  | 'document'
  | 'camera'
  | 'cloud'
  | 'refresh'
  | 'plus'
  | 'user-plus'
  | 'chevron-right'
  | 'chevron-left'
  | 'chevron-down'
  | 'chevron-up'
  | 'chevrons-left'
  | 'chevrons-right'
  | 'search'
  | 'bell'
  | 'menu'
  | 'close'
  | 'calendar'
  | 'clock'
  | 'alert-circle'
  | 'alert-triangle'
  | 'check-circle'
  | 'info'
  | 'arrow-left'
  | 'download'
  | 'edit'
  | 'more'
  | 'filter'
  | 'map-pin'
  | 'wrench'
  | 'activity'
  | 'gauge'
  | 'play'
  | 'stop'
  | 'flag'
  | 'link'
  | 'lock'
  | 'eye'
  | 'eye-off'
  | 'inbox'
  | 'clipboard'
  | 'user'
  | 'fuel'
  | 'book'
  | 'scale'
  | 'upload'
  | 'coins'
  | 'package'
  | 'clipboard-list'
  | 'shield-lock'
  | 'megaphone'
  | 'siren'
  | 'zap'
  | 'users'
  | 'target';

const glyphs: Record<IconName, LucideIcon> = {
  'dashboard': LayoutDashboard,
  'building': Building2,
  'layers': Layers,
  'truck': Truck,
  'driver': UserRound,
  'route': Route,
  'workflow': Workflow,
  'shield-check': ShieldCheck,
  'shield-alert': ShieldAlert,
  'document': FileText,
  'camera': Camera,
  'cloud': Cloud,
  'refresh': RefreshCw,
  'plus': Plus,
  'user-plus': UserPlus,
  'chevron-right': ChevronRight,
  'chevron-left': ChevronLeft,
  'chevron-down': ChevronDown,
  'chevron-up': ChevronUp,
  'chevrons-left': ChevronsLeft,
  'chevrons-right': ChevronsRight,
  'search': Search,
  'bell': Bell,
  'menu': Menu,
  'close': X,
  'calendar': Calendar,
  'clock': Clock,
  'alert-circle': CircleAlert,
  'alert-triangle': TriangleAlert,
  'check-circle': CircleCheck,
  'info': Info,
  'arrow-left': ArrowLeft,
  'download': Download,
  'edit': Pencil,
  'more': Ellipsis,
  'filter': ListFilter,
  'map-pin': MapPin,
  'wrench': Wrench,
  'activity': Activity,
  'gauge': Gauge,
  'play': Play,
  'stop': Square,
  'flag': Flag,
  'link': Link,
  'lock': Lock,
  'eye': Eye,
  'eye-off': EyeOff,
  'inbox': Inbox,
  'clipboard': Clipboard,
  'user': User,
  'fuel': Fuel,
  'book': Book,
  'scale': Scale,
  'upload': Upload,
  'coins': Coins,
  'package': Package,
  'clipboard-list': ClipboardList,
  'shield-lock': ShieldAlert,
  'megaphone': Megaphone,
  'siren': Siren,
  'zap': Zap,
  'users': Users,
  'target': Target,
};

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name' | 'ref'> {
  name: IconName;
  /** Pixel size; the glyph is square. */
  size?: number;
}

const Icon = ({ name, size = 20, className, ...rest }: IconProps) => {
  const Glyph = glyphs[name];
  return (
    <Glyph
      size={size}
      strokeWidth={1.7}
      aria-hidden="true"
      focusable="false"
      className={className}
      {...rest}
    />
  );
};

export default Icon;
