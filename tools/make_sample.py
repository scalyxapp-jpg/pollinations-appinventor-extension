#!/usr/bin/env python3
"""Builds the sample .aia for the Pollinations App Inventor extension.

The layout follows MIT App Inventor's own extension test fixture, and the
version numbers come from App Inventor's YaVersion.java, so the project is
described in exactly the terms the platform expects.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import zipfile
from pathlib import Path

# From appinventor-sources YaVersion.java (the version this was built against).
YA_VERSION = 237
BLOCKS_LANGUAGE_VERSION = 39
FORM_VERSION = 32
BUTTON_VERSION = 8
TEXTBOX_VERSION = 15
LABEL_VERSION = 6
IMAGE_VERSION = 8
ARRANGEMENT_VERSION = 4

PROJECT_NAME = "PollinationsComic"
SCREEN = "Screen1"
USER = "ai_pollinations"
EXTENSION_DIR = "dev.pollinations.ai2"
EXTENSION_CLASS = "dev.pollinations.ai2.Pollinations"

BLOCKS_HEADER = f'<xml xmlns="http://www.w3.org/1999/xhtml">'
BLOCKS_FOOTER = (
    f'  <yacodeblocks ya-version="{YA_VERSION}" '
    f'language-version="{BLOCKS_LANGUAGE_VERSION}"></yacodeblocks>\n</xml>'
)


def block_id(seed: int) -> str:
    """A unique id per block; App Inventor only needs uniqueness."""
    alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
    value = seed * 2654435761 % (2**48)
    return "".join(alphabet[(value >> shift) & 0x3F] for shift in range(0, 120, 6))


def event_block(
    seed: int,
    component_type: str,
    instance: str,
    event: str,
    body: str,
    x: int,
    y: int,
    param: str | None = None,
) -> str:
    """A component event such as Button1.Click, with one statement inside."""
    parameter = (
        f'      <value name="ARG0"><block type="lexical_variable_get" id="{block_id(seed + 90)}">'
        f'<field name="VAR">{param}</field></block></value>\n'
        if param
        else ""
    )
    return f"""  <block type="component_event" id="{block_id(seed)}" x="{x}" y="{y}">
    <mutation component_type="{component_type}" is_generic="false" instance_name="{instance}" event_name="{event}"></mutation>
    <field name="COMPONENT_SELECTOR">{instance}</field>
    <statement name="DO">
{body}{parameter}    </statement>
  </block>
"""


def method_block(
    seed: int,
    component_type: str,
    instance: str,
    method: str,
    argument: str | None = None,
) -> str:
    """A call such as Pollinations1.Ask, with one argument."""
    argument_xml = ""
    if argument is not None:
        argument_xml = f'        <value name="ARG0">\n{argument}\n        </value>\n'
    return f"""      <block type="component_method" id="{block_id(seed)}">
        <mutation component_type="{component_type}" method_name="{method}" is_generic="false" instance_name="{instance}"></mutation>
        <field name="COMPONENT_SELECTOR">{instance}</field>
{argument_xml}      </block>
"""


def get_property_block(seed: int, component_type: str, instance: str, prop: str) -> str:
    return f"""          <block type="component_set_get" id="{block_id(seed)}">
            <mutation component_type="{component_type}" set_or_get="get" property_name="{prop}" is_generic="false" instance_name="{instance}"></mutation>
            <field name="COMPONENT_SELECTOR">{instance}</field>
            <field name="PROP">{prop}</field>
          </block>"""


def set_property_block(
    seed: int, component_type: str, instance: str, prop: str, value: str
) -> str:
    return f"""      <block type="component_set_get" id="{block_id(seed)}">
        <mutation component_type="{component_type}" set_or_get="set" property_name="{prop}" is_generic="false" instance_name="{instance}"></mutation>
        <field name="COMPONENT_SELECTOR">{instance}</field>
        <field name="PROP">{prop}</field>
        <value name="VALUE">
{value}
        </value>
      </block>
"""


def text_block(seed: int, text: str) -> str:
    return f"""          <block type="text" id="{block_id(seed)}">
            <field name="TEXT">{text}</field>
          </block>"""


def build_blocks() -> str:
    parts = [BLOCKS_HEADER]

    # Button1.Click -> Pollinations1.Ask(TextBox1.Text)
    parts.append(
        event_block(
            1,
            "Button",
            "Button1",
            "Click",
            method_block(
                2,
                "Pollinations",
                "Pollinations1",
                "Ask",
                get_property_block(3, "TextBox", "TextBox1", "Text"),
            ),
            0,
            0,
        )
    )

    # Button2.Click -> Pollinations1.DownloadImage(TextBox1.Text, "comic.png")
    draw = method_block(
        11,
        "Pollinations",
        "Pollinations1",
        "DownloadImage",
        get_property_block(12, "TextBox", "TextBox1", "Text"),
    )
    draw = draw.replace(
        "      </block>\n",
        f'        <value name="ARG1">\n{text_block(13, "comic.png")}\n        </value>\n      </block>\n',
        1,
    )
    parts.append(event_block(10, "Button", "Button2", "Click", draw, 320, 0))

    # Pollinations1.GotText -> Label1.Text
    parts.append(
        event_block(
            20,
            "Pollinations",
            "Pollinations1",
            "GotText",
            set_property_block(
                21,
                "Label",
                "Label1",
                "Text",
                f'          <block type="lexical_variable_get" id="{block_id(22)}">'
                f'<field name="VAR">text</field></block>',
            ),
            0,
            260,
        )
    )

    # Pollinations1.GotImageFile -> Image1.Picture
    parts.append(
        event_block(
            30,
            "Pollinations",
            "Pollinations1",
            "GotImageFile",
            set_property_block(
                31,
                "Image",
                "Image1",
                "Picture",
                f'          <block type="lexical_variable_get" id="{block_id(32)}">'
                f'<field name="VAR">path</field></block>',
            ),
            420,
            260,
        )
    )

    # Pollinations1.GotError -> Label1.Text
    parts.append(
        event_block(
            40,
            "Pollinations",
            "Pollinations1",
            "GotError",
            set_property_block(
                41,
                "Label",
                "Label1",
                "Text",
                f'          <block type="lexical_variable_get" id="{block_id(42)}">'
                f'<field name="VAR">message</field></block>',
            ),
            760,
            260,
        )
    )

    parts.append(BLOCKS_FOOTER)
    return "\n".join(parts) + "\n"


def build_screen() -> str:
    """The designer file: one JSON blob inside a #| |# comment."""
    form = {
        "authURL": ["ai2.appinventor.mit.edu"],
        "YaVersion": str(YA_VERSION),
        "Source": "Form",
        "Properties": {
            "$Name": SCREEN,
            "$Type": "Form",
            "$Version": str(FORM_VERSION),
            "AppName": PROJECT_NAME,
            "Title": "Pollinations Comic",
            "Uuid": "0",
            "AlignHorizontal": "3",
            "Scrollable": "True",
            "$Components": [
                {
                    "$Name": "TextBox1",
                    "$Type": "TextBox",
                    "$Version": str(TEXTBOX_VERSION),
                    "Uuid": "-1000000001",
                    "Hint": "a lighthouse keeper who befriends a storm cloud",
                    "Width": "-2",
                },
                {
                    "$Name": "Button1",
                    "$Type": "Button",
                    "$Version": str(BUTTON_VERSION),
                    "Uuid": "-1000000002",
                    "Text": "Ask",
                    "Width": "-2",
                },
                {
                    "$Name": "Button2",
                    "$Type": "Button",
                    "$Version": str(BUTTON_VERSION),
                    "Uuid": "-1000000003",
                    "Text": "Draw a panel",
                    "Width": "-2",
                },
                {
                    "$Name": "Label1",
                    "$Type": "Label",
                    "$Version": str(LABEL_VERSION),
                    "Uuid": "-1000000004",
                    "Text": "Set the API key, then press Ask or sign in.",
                    "Width": "-2",
                },
                {
                    "$Name": "Image1",
                    "$Type": "Image",
                    "$Version": str(IMAGE_VERSION),
                    "Uuid": "-1000000005",
                    "Width": "-2",
                    "Height": "-2",
                },
                {
                    "$Name": "Pollinations1",
                    "$Type": "Pollinations",
                    "$Version": "1",
                    "Uuid": "-1000000006",
                },
            ],
        },
    }
    return "#|\n$JSON\n" + json.dumps(form, separators=(",", ":")) + "\n|#\n"


def build_properties() -> str:
    return (
        "\n".join(
            [
                f"main=appinventor.{USER}.{PROJECT_NAME}.{SCREEN}",
                f"name={PROJECT_NAME}",
                "assets=../assets",
                "source=../src",
                "build=../build",
                "versioncode=1",
                "versionname=1.0",
                "useslocation=False",
                f"aname={PROJECT_NAME}",
                "sizing=Fixed",
                "showlistsasjson=False",
                "color=0&0&0",
                "theme=Classic",
            ]
        )
        + "\n"
    )


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: make_sample.py <built.aix> <output.aia>")
        return 2

    aix, target = Path(sys.argv[1]), Path(sys.argv[2])
    if not aix.exists():
        print(f"extension not found: {aix}")
        return 1

    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        # The project files.
        archive.writestr("youngandroidproject/project.properties", build_properties())
        base = f"src/appinventor/{USER}/{PROJECT_NAME}"
        archive.writestr(f"{base}/{SCREEN}.scm", build_screen())
        archive.writestr(f"{base}/{SCREEN}.bky", build_blocks())

        # The extension files, with the .aix's own package folder stripped:
        # App Inventor expects them directly under assets/external_comps/<package>/.
        prefix = f"{EXTENSION_DIR}/"
        with zipfile.ZipFile(aix) as source:
            for name in source.namelist():
                if name.endswith("/"):
                    continue
                relative = name[len(prefix):] if name.startswith(prefix) else name
                archive.writestr(f"assets/external_comps/{EXTENSION_DIR}/{relative}", source.read(name))

    print(f"wrote {target} ({target.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
