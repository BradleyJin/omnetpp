"""
A collection of utility function for data manipulation and plotting, built
on top of Pandas data frames and the `chart` and `plot` packages from `omnetpp.scave`.
Functions in this module have been written largely to the needs of the
chart templates that ship with the IDE.
"""

import random, sys, os, string, re, math, importlib
import numpy as np
import scipy.stats as st
import pandas as pd
import matplotlib as mpl
import matplotlib.pyplot as plt
from itertools import cycle
from omnetpp.scave import chart, plot, vectorops

_marker_cycle = None
_color_cycle = None


def confidence_interval(alpha, data):
    """
    Returns the half-length of the confidence interval of the mean of `data`, assuming
    normal distribution, for the given confidence level `alpha`.
    `alpha` must be in the [0..1] range.
    """
    return st.norm.interval(alpha, loc=0, scale=st.sem(data))[1]

def split(s, sep=','):
    """
    Split a string with the given separator (by default with comma), trim
    the surrounding whitespace from the items, and return the result as a
    list. Return an empty list for an empty or all-whitespace input string.
    """
    parts = s.split(sep)
    parts = [p.strip() for p in parts]
    if parts == ['']:
        parts = []
    return parts

def extract_label_columns(df, preferred_legend_column="title"):
    """
    Utility function to make a reasonable guess as to which column of
    the given DataFrame is most suitable to act as a chart title and
    which ones can be used as legend labels.

    Ideally a "title column" should be one in which all lines have the same
    value, and can be reasonably used as a title. Some often used candidates
    are: `title`, `name`, and `module`.

    Label columns should be a minimal set of columns whose corresponding
    value tuples uniquely identify every line in the DataFrame. These will
    primarily be iteration variables and run attributes.

    # Returns:

    A pair of a string and a list; the first value is the name of the
    "title" column, and the second one is a list of pairs, each
    containing the index and the name of a "label" column.

    Example: `('title', [(8, 'numHosts'), (7, 'iaMean')])`
    """

    titles = ["title", "name", "module", "experiment", "measurement", "replication"]
    titles.remove(preferred_legend_column)
    titles = [preferred_legend_column] + titles

    legends = titles

    blacklist = ["runID", "value", "attrvalue", "vectime", "vecvalue",
                 "binedges", "binvalues", "underflows", "overflows",
                 "count", "sumweights", "mean", "stddev", "min", "max",
                 "processid", "datetime", "datetimef", "runnumber", "seedset",
                 "iterationvars", "iterationvarsf", "iterationvarsd",
                 "source", "interpolationmode", "enum", "title", "unit"]

    title_col = None

    for title in titles:
        if title in df and len(df[title].unique()) == 1:
            title_col = title
            break
    if title_col == None:
        if "name" in titles:
            title_col = "name"

    legend_cols = []

    for legend in legends:
        if legend in df and len(df[legend].unique()) == len(df):
            legend_cols = [(list(df.columns.values).index(legend), legend)]
            break

    if legend_cols:
        return title_col, legend_cols

    last_len = None
    for i, col in list(enumerate(df)):
        if col not in blacklist and col != title_col:
            new_len = len(df.groupby([i2 for i1, i2 in legend_cols] + [col]))
            if new_len == len(df) or not last_len or new_len > last_len:
                legend_cols.append((i, col))
                last_len = new_len
            if new_len == len(df):
                break

    # filter out columns which only have a single value in them (this can happen in the loop above, with the first considered column)
    legend_cols = list(filter(lambda icol: len(df[icol[1]].unique()) > 1, legend_cols))

    """
    if not legend_cols:
        last_len = None
        for i, col in reversed(list(enumerate(df))):
            if col not in blacklist and col != title_col:
                new_len = len(df.groupby([i2 for i1, i2 in legend_cols] + [col]))
                if new_len > 1:
                    legend_cols.append((i, col))
    """

    # TODO: use runID (or iterationvars?) as last resort (if present)

    # at this point, ideally this should hold: len(df.groupby([i2 for i1, i2 in legend_cols])) == len(df)
    return title_col, legend_cols


def make_legend_label(legend_cols, row):
    """
    Produces a reasonably good label text (to be used in a chart legend) for a result row from
    a DataFrame, given a list of selected columns as returned by `extract_label_columns()`.
    """

    if len(legend_cols) == 1:
        return str(row[legend_cols[0][0]])
    return ", ".join([col + "=" + str(row[i]) for i, col in legend_cols])


def make_chart_title(df, title_col, legend_cols):
    """
    Produces a reasonably good chart title text from a result DataFrame, given a selected "title"
    column, and a list of selected "legend" columns as returned by `extract_label_columns()`.
    """
    if df is None or df.empty or title_col not in df:
        return "None"

    what = str(list(df[title_col])[0]) if title_col else "Data"
    if title_col and len(df[title_col].unique()) > 1:
        what += " and other variables"
    by_what = (" (by " + ", ".join([id[1] for id in legend_cols]) + ")") if legend_cols else ""
    return what + by_what


def pick_two_columns(df):
    title_col, label_cols = extract_label_columns(df)
    label_cols = [l[1] for l in label_cols]
    if len(label_cols) == 0:
        return None, None
    if len(label_cols) == 1:
        if label_cols[0] == title_col:
            return None, None
        else:
            return title_col, label_cols[0]
    if len(label_cols) >= 2:
        return label_cols[0], label_cols[1]


def assert_columns_exist(df, cols, message="Expected column missing from DataFrame"):
    for c in cols:
        if c not in df:
            plot.set_warning(message + ": " + c)
            exit(1)


def parse_rcparams(rc_content):
    rc_temp = {}
    for line_no, line in enumerate(rc_content.split("\n"), 1):
        strippedline = line.split('#', 1)[0].strip()
        if not strippedline:
            continue
        tup = strippedline.split(':', 1)
        if len(tup) != 2:
            raise RuntimeError("Illegal rc line #" + str(line_no)) + ": " + line
        key, val = tup
        key = key.strip()
        val = val.strip()
        if key in rc_temp:
            raise RuntimeError("Duplicate key " + key + " on line " + str(line_no))
        rc_temp[key] = val

    return rc_temp


def _filter_by_key_prefix(props, prefix):
    return {k[len(prefix):] : v for (k, v) in props.items() if k.startswith(prefix) and v}

def _parse_optional_bool(value):
    if value is None:
        return None
    if value.lower() not in ["true", "false"]:
        raise ValueError("Invalid boolean property value: " + value)
    return value.lower() == "true"

def make_fancy_xticklabels(ax):

    import math
    import numpy as np
    from matplotlib.text import Text

    # Declare and register callbacks
    def on_xlims_change(ax):
        display_xs = list()
        fontheight = 24
        for p in ax.get_xticks():
            xdisplay, ydisplay = ax.transData.transform_point((p, 0))
            display_xs.append(xdisplay)
        dxa = np.array(display_xs)
        dxd = dxa[1:] - dxa[:-1]
        min_dx = dxd.min() + 12

        tes = list()
        for l in ax.get_xticklabels():
            text = Text(text=l, figure=ax.figure)
            extent = text.get_window_extent()
            tes.append(extent.width)
            fontheight = extent.height

        max_tx = np.array(tes).max()

        if min_dx > max_tx:
            angle = 0
        elif min_dx < fontheight * 2:
            angle = 90
        else:
            angle = math.atan(fontheight / (min_dx - fontheight * 2)) / math.pi * 180

        angle = max(0, min(90, angle))

        plt.xticks(rotation=angle, horizontalalignment="right")
        plt.tight_layout()

    ax.callbacks.connect('xlim_changed', on_xlims_change)


# inspired by: https://stackoverflow.com/a/11562898/635587
# and https://stackoverflow.com/q/11551049#comment77920445_11562898
def make_scroll_navigable(figure):
    def zoom_fun(event):
        ax = event.inaxes

        if ax is None:
            return

        SCALING_FACTOR = 1.5
        PANNING_FACTOR = 0.1

        cur_xlim = ax.get_xlim()
        cur_ylim = ax.get_ylim()
        cur_xrange = cur_xlim[1] - cur_xlim[0]
        cur_yrange = cur_ylim[1] - cur_ylim[0]
        xdata = event.xdata
        ydata = event.ydata
        direction = np.sign(event.step)

        if event.key is None: # vertical pan
            delta = cur_yrange * direction * PANNING_FACTOR
            ax.set_ylim([cur_ylim[0] + delta, cur_ylim[1] + delta])
        elif event.key == "shift": # horizontal pan
            delta = cur_xrange * -direction * PANNING_FACTOR
            ax.set_xlim([cur_xlim[0] + delta, cur_xlim[1] + delta])
        elif event.key == "control": # zoom
            scale = math.pow(SCALING_FACTOR, direction)
            ax.set_xlim([xdata - (xdata-cur_xlim[0]) / scale,
                        xdata + (cur_xlim[1]-xdata) / scale])
            ax.set_ylim([ydata - (ydata-cur_ylim[0]) / scale,
                        ydata + (cur_ylim[1]-ydata) / scale])

        plt.draw()

    figure.canvas.mpl_connect('scroll_event', zoom_fun)


# source: https://stackoverflow.com/a/39789718/635587
def customized_box_plot(percentiles, axes, redraw = True, *args, **kwargs):
    """
    Generates a customized boxplot based on the given percentile values
    """
    n_box = len(percentiles)
    box_plot = axes.boxplot([[-9, -4, 2, 4, 9],]*n_box, showmeans=True, meanprops=dict(marker='+'), *args, **kwargs)
    # Creates len(percentiles) no of box plots

    min_y, max_y = float('inf'), -float('inf')

    for box_no, pdata in enumerate(percentiles):
        if len(pdata) == 6:
            (q1_start, q2_start, q3_start, q4_start, q4_end, fliers_xy) = pdata
        elif len(pdata) == 5:
            (q1_start, q2_start, q3_start, q4_start, q4_end) = pdata
            fliers_xy = None
        else:
            raise ValueError("Percentile arrays for customized_box_plot must have either 5 or 6 values")

        # Lower cap
        box_plot['caps'][2*box_no].set_ydata([q1_start, q1_start])
        # xdata is determined by the width of the box plot

        # Lower whiskers
        box_plot['whiskers'][2*box_no].set_ydata([q1_start, q2_start])

        # Higher cap
        box_plot['caps'][2*box_no + 1].set_ydata([q4_end, q4_end])

        # Higher whiskers
        box_plot['whiskers'][2*box_no + 1].set_ydata([q4_start, q4_end])

        # Box
        path = box_plot['boxes'][box_no].get_path()
        path.vertices[0][1] = q2_start
        path.vertices[1][1] = q2_start
        path.vertices[2][1] = q4_start
        path.vertices[3][1] = q4_start
        path.vertices[4][1] = q2_start

        box_plot['means'][box_no].set_ydata([q3_start, q3_start])
        box_plot['medians'][box_no].set_visible(False)

        # Outliers
        if fliers_xy is not None and len(fliers_xy[0]) != 0:
            # If outliers exist
            box_plot['fliers'][box_no].set(xdata = fliers_xy[0],
                                           ydata = fliers_xy[1])

            min_y = min(q1_start, min_y, fliers_xy[1].min())
            max_y = max(q4_end, max_y, fliers_xy[1].max())

        else:
            min_y = min(q1_start, min_y)
            max_y = max(q4_end, max_y)

        mid_y = (min_y + max_y) / 2
        # The y axis is rescaled to fit the new box plot completely with 10%
        # of the maximum value at both ends
        axes.set_ylim([mid_y - (mid_y - min_y) * 1.25, mid_y + (max_y - mid_y) * 1.25])

    # If redraw is set to true, the canvas is updated.
    if redraw:
        axes.figure.canvas.draw()

    return box_plot


def interpolationmode_to_drawstyle(interpolationmode, hasenum):
    interp = interpolationmode if interpolationmode else 'sample-hold' if hasenum else None
    if not interp:
        ds = "default"
    elif interp == "none":
        ds = "none"
    elif interp == "linear":
        ds = "default"
    elif interp == "sample-hold":
        ds = "steps-post"
    elif interp == "backward-sample-hold":
        ds = 'steps-pre'
    else:
        print("Unknown interpolationmode:", interp, file=sys.stderr)
        ds = None

    return ds


def _make_line_args(props, t, df):
    global _marker_cycle
    style = dict()

    def get_prop(k):
        return props[k] if k in props else None

    if get_prop("linestyle"):
        style["linestyle"] = get_prop("linestyle")  # note: must precede 'drawstyle' setting

    ds = get_prop("drawstyle")
    if not ds or ds == "auto":
        interpolationmode = t.interpolationmode if "interpolationmode" in df else None
        hasenum = "enum" in df
        ds = interpolationmode_to_drawstyle(interpolationmode, hasenum)

    if ds == "none":
        style["linestyle"] = " "
        style["drawstyle"] = "default"  # or any valid value, doesn't matter
    else:
        style["drawstyle"] = ds if ds != "linear" else "default"

    if get_prop("linecolor"):
        style["color"] = get_prop("linecolor")
    else:
        style["color"] = next(_color_cycle)

    if get_prop("linewidth"):
        style["linewidth"] = get_prop("linewidth")

    if not get_prop("marker") or get_prop("marker") == "auto":
        style["marker"] = next(_marker_cycle)
    elif get_prop("marker") == 'none':
        style["marker"] = ' '
    else:
        style["marker"] = get_prop("marker")[0] # take first character only, as Matplotlib uses 1-char codes; this allows us to include a description

    if get_prop("markersize"):
        style["markersize"] = get_prop("markersize")

    # Special case: not letting both the lines and the markers to become
    # invisible automatically. Only if the user specifically asks for it.
    if style["marker"] == ' ' and style["linestyle"] == ' ':
        orig_ds = get_prop("drawstyle")
        if not orig_ds or orig_ds == "auto":
            style["marker"] = '.'

    return style

def _make_histline_args(props, t, df): # ??? is t and df needed at all?
    style = dict()

    def get_prop(k):
        return props[k] if k in props else None

    if get_prop("color"):
        style["color"] = get_prop("color")
    else:
        style["color"] = next(_color_cycle)

    if get_prop("linewidth"):
        style["linewidth"] = get_prop("linewidth")

    ds = get_prop("drawstyle")
    if ds == "Solid":
        style["histtype"] = "stepfilled"
    elif ds == "Outline":
        style["histtype"] = "step"

    style["density"] = _parse_optional_bool(get_prop("normalize"))
    style["cumulative"] = _parse_optional_bool(get_prop("cumulative"))

    return style


def _make_bar_args(props, df): # ??? is df needed at all? should we also get the column name?
    style = dict()

    def get_prop(k):
        return props[k] if k in props else None

    if get_prop("color"):
        style["color"] = get_prop("color")
    else:
        style["color"] = next(_color_cycle)

    return style


def get_names_for_title(df, props):
    def get_prop(k):
        return props[k] if k in props else None

    if get_prop("legend_labels") == "result titles" and "title" in df:
        series = df["title"].fillna(df["name"])
    else:
        series = df["name"]

    return series.unique()


def set_plot_title(title, suggested_chart_name=None):
    if chart.is_native_chart():
        plot.title(title)
    else:
        plt.title(title)
    chart.set_suggested_chart_name(suggested_chart_name if suggested_chart_name is not None else title)


def _to_label(x):
    """
    Internal. Turns various types of objects into a string, so they can be used as labels.
    """
    if x is None:
        return ""
    elif isinstance(x, str):
        return x
    elif isinstance(x, tuple):
        return ", ".join(map(str, list(x)))
    elif isinstance(x, list):
        return ", ".join(map(str, x))
    else:
        return str(x)


def plot_bars(df, props, names=None, errors_df=None):
    p = plot if chart.is_native_chart() else plt

    def get_prop(k):
        return props[k] if k in props else None

    group_fill_ratio = 0.8
    aligned_bar_fill_ratio = 0.9
    overlap_visible_fraction = 1 / 3

    # used only by the mpl charts
    xs = np.arange(len(df.index), dtype=np.float64)  # the x locations for the groups
    width = group_fill_ratio / len(df.columns)  # the width of the bars
    bottoms = np.zeros_like(xs)
    group_increment = 0.0

    baseline = get_prop("baseline")
    if baseline:
        if chart.is_native_chart(): # is this how this should be done?
            plot.set_property("Bars.Baseline", baseline)
        else:
            bottoms += float(baseline)

    extra_args = dict()

    placement = get_prop("bar_placement")
    if placement:
        if chart.is_native_chart(): # is this how this should be done?
            plot.set_property("Bar.Placement", placement)
        else:
            extra_args["bottom"] = bottoms
            if placement == "InFront":
                width = group_fill_ratio
            elif placement == "Stacked":
                width = group_fill_ratio
            elif placement == "Aligned":
                width = group_fill_ratio / len(df.columns)
                group_increment = width
                xs -= width * (len(df.columns)-1)/2
                width *= aligned_bar_fill_ratio
            elif placement == "Overlap":
                width = group_fill_ratio / (1 + len(df.columns) * overlap_visible_fraction)
                group_increment = width * overlap_visible_fraction
                extra_parts = (1.0 / overlap_visible_fraction - 1)
                xs += width / extra_parts - (len(df.columns) + extra_parts) * width * overlap_visible_fraction / 2

    for i, column in enumerate(df):
        style = _make_bar_args(props, df)

        if not chart.is_native_chart(): # FIXME: noot pretty...
            extra_args['zorder'] = 1 - (i / len(df.columns) / 10)

        label = df.columns.name + "=" + _to_label(column) if df.columns.name else _to_label(column)
        ys = df[column].values
        p.bar(xs, ys, width, label=label, **extra_args, **style)

        if not chart.is_native_chart() and errors_df is not None:
            plt.errorbar(xs, ys + bottoms, yerr=errors_df[column], capsize=float(get_prop("cap_size") or 4), **style, linestyle="none", ecolor=mpl.rcParams["axes.edgecolor"])

        xs += group_increment
        if placement == "Stacked":
            bottoms += df[column].values

    rotation = get_prop("xlabel_rotation")
    if rotation:
        rotation = float(rotation)
    else:
        rotation = 0
    p.xticks(list(range(len(df.index))), list([_to_label(i) for i in df.index.values]), rotation=rotation)

    # Add some text for labels, title and custom x-axis tick labels, etc.
    groups = (get_prop("groups") or "").split(",")
    series = (get_prop("series") or "").split(",")

    p.xlabel(_to_label(df.index.names[0]))

    title = ""
    if len(names):
        names_str = [str(n) for n in names]
        groups_series_str = [str(gs) for gs in groups+series]
        p.ylabel(", ".join(names_str))
        title = ", ".join(names_str)
        if groups_series_str and groups_series_str[0]:
            title += " by " + ", ".join(groups_series_str)

    set_plot_title(get_prop("title") or title)


def plot_vectors(df, props):
    p = plot if chart.is_native_chart() else plt

    def get_prop(k):
        return props[k] if k in props else None

    column = "name" if get_prop("legend_labels") == "result names" else "title"
    title_col, legend_cols = extract_label_columns(df, column)

    for i, t in enumerate(df.itertuples(index=False)):
        style = _make_line_args(props, t, df)
        p.plot(t.vectime, t.vecvalue, label=make_legend_label(legend_cols, t), **style)

    title = get_prop("title") or make_chart_title(df, title_col, legend_cols)
    set_plot_title(title)

def plot_histograms(df, props):
    p = plot if chart.is_native_chart() else plt

    def get_prop(k):
        return props[k] if k in props else None

    title_col, legend_cols = extract_label_columns(df)

    for i, t in enumerate(df.itertuples(index=False)):
        style = _make_histline_args(props, t, df)

        # We are branching here, and call the two .hist implementations
        # differently, because the MPL API does not have support for
        # underflows/overflows, so we have to "fake" them into the real
        # data as additional cells. Drawing them separately wouldn't
        # work, because that would change the histogram when the
        # "normalized" or "cumulative" transforms are done (by MPL).
        if chart.is_native_chart():
            p.hist(t.binedges[:-1], t.binedges, weights=t.binvalues, label=make_legend_label(legend_cols, t),
                   minvalue=t.min, maxvalue=t.max, underflows=t.underflows, overflows=t.overflows, **style)
        else:
            edges = list(t.binedges)
            values = list(t.binvalues)
            has_underflow_bin = not np.isnan(t.min) and not np.isnan(t.underflows) and t.min < edges[0]
            has_overflow_bin = not np.isnan(t.max) and not np.isnan(t.overflows) and t.max >= edges[-1]
            if has_underflow_bin:
                edges = [t.min] + edges
                values = [t.underflows] + values
            if has_overflow_bin:
                edges = edges + [t.max]
                values = values + [t.overflows]

            p.hist(edges[:-1], edges, weights=values, label=make_legend_label(legend_cols, t), **style)

    show_overflows = get_prop("show_overflows")
    if show_overflows and chart.is_native_chart():
        plot.set_property("Hist.ShowOverflowCell", str(_parse_optional_bool(show_overflows)).lower())

    title = get_prop("title") or make_chart_title(df, title_col, legend_cols)
    set_plot_title(title)


def _initialize_cycles(props):
    def get_prop(k):
        return props[k] if k in props else None

    global _marker_cycle
    global _color_cycle

    seed = int(get_prop('cycle_seed') or 0)
    r = random.Random(seed)

    ml = list("osv^<>pDd")
    if seed != 0:
        random.shuffle(ml, r.random)
    _marker_cycle = cycle(ml)

    prop_cycler = plt.rcParams['axes.prop_cycle']

    if chart.is_native_chart():
        num_colors = 10
    else:
        num_colors = len(prop_cycler.by_key()['color']) if "color" in prop_cycler.keys else 1

    cl = ["C" + str(i) for i in range(num_colors)]
    if seed != 0:
        random.shuffle(cl, r.random)
    _color_cycle = cycle(cl)


def _parse_vectorop_line(line):
    line = line.strip()
    m = re.match(r"((\w+)\s*:)?\s*(([\w.]+)\.)?(\w+)?(.*)", line)  # always matches due to last group
    _, type, _, module, name, rest = m.groups()
    if not name:
        if line and not line.startswith('#'):
            raise SyntaxError("Syntax error")
        return (None, None, None, None, None)
    if not type:
        type = "apply"
    if not type in ['apply', 'compute']:
        raise SyntaxError("Syntax error near '"+type+"': must be 'apply' or 'compute' (or omitted)")
    rest = rest.strip()
    if not rest or rest.startswith('#'):
        rest = "()"
    if not rest.startswith('('):
        raise ValueError("Parenthesized argument list expected after operation name")
    try:
        def return_args(*args, **kwargs):
            return (args,kwargs)
        args, kwargs = eval(name + " " + rest, None, {name: return_args}) # let Python do the parsing, incl. comment discarding
    except SyntaxError:
        raise SyntaxError("Syntax error in argument list")
    except Exception as e:
        raise ValueError("Error in argument list: " + str(e))
    return type, module, name, args, kwargs


def perform_vector_op(df, line):
    # parse line
    type, module, name, args, kwargs = _parse_vectorop_line(line)
    if name is None: # empty line
        return df

    # look up function
    function = None
    if not module and name in globals():  # unfortunately this doesn't work, because the chart script sees a different "globals" dict than we in this module, due it being called via exec()
        function = globals()[name]
    else:
        if not module:
            module = "omnetpp.scave.vectorops"
        mod = importlib.import_module(module)
        if not name in mod.__dict__:
            raise ValueError("Vector filter function '" + name + "' not found in module '" + module + "'")
        function = mod.__dict__[name]

    # perform operation
    if type == "apply":
        df = apply_vector_op(df, function, *args, **kwargs)
    elif type == "compute":
        df = compute_vector_op(df, function, *args, **kwargs)
    return df


def perform_vector_ops(df, operations : str):
    if not operations:
        return df
    line_num = 0
    for line in operations.splitlines():
        line_num += 1
        try:
            df = perform_vector_op(df, line)
        except Exception as e:
            context = " in Vector Operations line " + str(line_num) + " \"" + line.strip() + "\""
            raise type(e)(str(e) + context).with_traceback(sys.exc_info()[2]) # re-throw with context
    return df


def apply_vector_op(dataframe, operation, *args, **kwargs):
    if operation == vectorops.aggregate:
        return vectorops.aggregate(dataframe, *args, **kwargs)
    elif operation == vectorops.merge:
        return vectorops.merge(dataframe)
    else:
        condition = kwargs.pop('condition', None)
        clone = dataframe.copy()
        clone = clone.transform(lambda row: operation(row.copy(), *args, **kwargs) if not condition or condition(row) else row, axis='columns')
        return clone


def compute_vector_op(dataframe, operation, *args, **kwargs):
    if operation == vectorops.aggregate:
        return dataframe.append(vectorops.aggregate(dataframe, *args, **kwargs), sort=False)
    elif operation == vectorops.merge:
        return dataframe.append(vectorops.merge(dataframe), sort=False)
    else:
        condition = kwargs.pop('condition', None)
        clone = dataframe.copy()
        clone = clone.transform(lambda row: operation(row.copy(), *args, **kwargs) if not condition or condition(row) else row, axis='columns')
        return dataframe.append(clone, sort=False)


def preconfigure_plot(props):
    def get_prop(k):
        return props[k] if k in props else None

    if chart.is_native_chart():
        #plot.set_properties(_filter_by_key_prefix(props,"plot."))  #TODO this was after plotting, was that intentional?
        supported_keys = plot.get_supported_property_keys()
        plot.set_properties({ k: v for k, v in props.items() if k in supported_keys})
        plot.set_properties(parse_rcparams(get_prop("plot.properties") or ""))
    else:
        if get_prop("plt.style"):
            plt.style.use(get_prop("plt.style"))
        mpl.rcParams.update(_filter_by_key_prefix(props,"matplotlibrc."))
        mpl.rcParams.update(parse_rcparams(get_prop("matplotlibrc") or ""))
        make_scroll_navigable(plt.gcf())

    _initialize_cycles(props)


def postconfigure_plot(props):
    p = plot if chart.is_native_chart() else plt

    def get_prop(k):
        return props[k] if k in props else None

    if get_prop("yaxis_title"):
        p.xlabel(get_prop("xaxis_title"))
    if get_prop("yaxis_title"):
        p.ylabel(get_prop("yaxis_title"))

    if get_prop("xaxis_min"):
        p.xlim(left=float(get_prop("xaxis_min")))
    if get_prop("xaxis_max"):
        p.xlim(right=float(get_prop("xaxis_max")))
    if get_prop("yaxis_min"):
        p.ylim(bottom=float(get_prop("yaxis_min")))
    if get_prop("yaxis_max"):
        p.ylim(top=float(get_prop("yaxis_max")))

    if get_prop("xaxis_log"):
        p.xscale("log" if _parse_optional_bool(get_prop("xaxis_log")) else "linear")
    if get_prop("yaxis_log"):
        p.yscale("log" if _parse_optional_bool(get_prop("yaxis_log")) else "linear")

    plot.legend(show=_parse_optional_bool(get_prop("legend_show")),
           frameon=_parse_optional_bool(get_prop("legend_border")),
           loc=get_prop("legend_placement"))

    p.grid(_parse_optional_bool(get_prop("grid_show")),
             "major" if (get_prop("grid_density") or "").lower() == "major" else "both") # grid_density is "Major" or "All"

    if not chart.is_native_chart():
        plt.tight_layout()

def export_image_if_needed(props):
    def get_prop(k):
        return props[k] if k in props else None

    if _parse_optional_bool(get_prop("export_image")):
        format = get_prop("image_export_format") or "svg"
        folder = get_prop("image_export_folder") or os.getcwd()
        filename = get_prop("image_export_filename") or _sanitize_filename(chart.get_name())
        filepath = os.path.join(folder, filename) + "." + format #TODO make it better
        width = float(get_prop("image_export_width") or 6)
        height = float(get_prop("image_export_height") or 4)
        dpi = get_prop("image_export_dpi") or "96"

        print("exporting image to: '" + filepath + "' as " + format)

        plt.gcf().set_size_inches(width, height)
        plt.savefig(filepath, format=format, dpi=int(dpi))


def export_data_if_needed(df, props):
    def get_prop(k):
        return props[k] if k in props else None

    def printer(arr):
        return np.array_str(arr)

    if _parse_optional_bool(get_prop("export_data")):
        format = "csv"
        folder = get_prop("image_export_folder") or os.getcwd()
        filename = get_prop("image_export_filename") or _sanitize_filename(chart.get_name())
        filepath = os.path.join(folder, filename) + "." + format #TODO make it better

        print("exporting data to: '" + filepath + "' as " + format)

        old_opts = np.get_printoptions()
        np.set_printoptions(threshold=np.inf, linewidth=np.inf)
        np.set_string_function(printer, False)
        pd.set_option('display.max_columns', None)
        pd.set_option('display.max_colwidth', None)
        df.to_csv(filepath)
        np.set_string_function(None, False)
        np.set_printoptions(**old_opts)


def _sanitize_filename(filename):
    valid_chars = "-_.() %s%s" % (string.ascii_letters, string.digits)
    return ''.join(c for c in filename if c in valid_chars)
